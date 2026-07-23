package dev.alvo.pieria.onboarding;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.api.request.OnboardPlanRequest;
import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.code.CodeIndexingService;
import dev.alvo.pieria.code.CodeIndexingService.SourceFile;
import dev.alvo.pieria.code.CodeParser;
import dev.alvo.pieria.code.CodeParser.ParseResult;
import dev.alvo.pieria.code.CodeParser.ParsedSymbol;
import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.VerifyMode;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.ingestion.Chunker;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.ingestion.TranscriptNormalizer;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.reminiscence.ReminiscenceService;
import dev.alvo.pieria.storage.SqliteCodeIndexStore;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import dev.alvo.pieria.task.TaskRegistry;
import dev.alvo.pieria.task.TaskStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Real temporary-SQLite coverage for concurrent content ingestion and deterministic code writes. */
class OnboardingConcurrentSqliteTests {

  @Test
  void concurrentContentAndCodeWritesPersistWithoutLockFailures(@TempDir Path directory) throws Exception {
    Path db = directory.resolve("onboard.db");
    try (HikariDataSource dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + db.toAbsolutePath() + "?busy_timeout=5000&transaction_mode=IMMEDIATE")
      .build()) {
      dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
      Flyway.configure().dataSource(dataSource).load().migrate();
      JdbcClient jdbc = JdbcClient.create(dataSource);
      SqliteMemoryStore memories = new SqliteMemoryStore(jdbc);
      SqliteCodeIndexStore codeStore = new SqliteCodeIndexStore(jdbc);

      TranscriptNormalizer normalizer = new TranscriptNormalizer();
      PieriaProperties properties = new PieriaProperties(null, null, null,
        new PieriaProperties.Model("small", "large", "embed", 1024, 4, null, null),
        new PieriaProperties.Ingestion(10_000, 2, 4, VerifyMode.ALWAYS,
          1, 0, 0, false, 32, 5, false, 5_000), null, null);
      IngestionService ingestion = new IngestionService(memories, new FakeModelGateway(), normalizer,
        new Chunker(normalizer), EffectiveConfigResolver.withoutOverrides(properties));
      ContentIngestor contentIngestor = new ContentIngestor(ingestion, memories);

      CodeParser parser = new CodeParser() {
        public boolean supports(String language) { return "java".equals(language); }
        public ParseResult parse(ParseInput input) {
          String name = input.repoRelPath().replace(".java", "");
          return new ParseResult(List.of(new ParsedSymbol(CodeSymbolKind.CLASS, name, name,
            "class " + name, "public", 1, 1, null)), List.of());
        }
      };
      CodeIndexingService codeIndexing = new CodeIndexingService(memories, codeStore, List.of(parser),
        new DataSourceTransactionManager(dataSource));
      CyclicBarrier start = new CyclicBarrier(2);

      OnboardingSource<SourceSpec.Markdown> content = new OnboardingSource<>() {
        public Class<SourceSpec.Markdown> specType() { return SourceSpec.Markdown.class; }
        public OnboardingWork begin(String profile, SourceSpec.Markdown spec,
                                    IngestProgressListener progress) {
          await(start);
          List<ContentDocument> documents = new ArrayList<>();
          for (int i = 0; i < 20; i++) {
            documents.add(new ContentDocument("docs/doc" + i + ".md", "durable content " + i));
          }
          return OnboardingWork.completed(contentIngestor.ingest(profile, "markdown", documents,
            null, false, progress));
        }
      };
      OnboardingSource<SourceSpec.SourceCode> code = new OnboardingSource<>() {
        public Class<SourceSpec.SourceCode> specType() { return SourceSpec.SourceCode.class; }
        public OnboardingWork begin(String profile, SourceSpec.SourceCode spec,
                                    IngestProgressListener progress) {
          await(start);
          List<SourceFile> files = new ArrayList<>();
          for (int i = 0; i < 20; i++) {
            files.add(new SourceFile("Class" + i + ".java", "java", "hash" + i,
              "public class Class" + i + " {}"));
          }
          var summary = codeIndexing.index(profile, null, files, false, progress);
          return ignored -> OnboardResult.code(summary.filesReceived(), summary.memoriesStored(),
            summary.symbols(), summary.resolvedEdges() + summary.heuristicEdges(), 0);
        }
      };

      OnboardingPlanService service = new OnboardingPlanService(
        new OnboardingService(List.of(content, code)), mock(ReminiscenceService.class),
        mock(TaskRegistry.class), new ObjectMapper());
      TaskRegistry host = new TaskRegistry();
      AtomicReference<OnboardPlanResult> result = new AtomicReference<>();
      UUID id = host.submit("onboard", "sqlite", progress -> {
        result.set(service.ingest("sqlite", new OnboardPlanRequest(List.of(
          new SourceSpec.Markdown("ignored", false, null, null),
          new SourceSpec.SourceCode("ignored", false, false, null)), false), progress));
        return JsonNodeFactory.instance.nullNode();
      });

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (host.find(id).orElseThrow().status() == TaskStatus.RUNNING && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertThat(host.find(id).orElseThrow().status()).isEqualTo(TaskStatus.SUCCEEDED);
      assertThat(result.get().errors()).isEmpty();
      assertThat(result.get().sources()).hasSize(2);
      String profileId = memories.findProfile("sqlite").orElseThrow().id();
      assertThat(codeStore.counts(profileId).files()).isEqualTo(20);
      assertThat(codeStore.counts(profileId).symbols()).isEqualTo(20);
      assertThat(memories.listMemories(profileId, MemoryType.FACT, null)).hasSizeGreaterThanOrEqualTo(21);
    }
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await(2, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
