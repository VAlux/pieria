package dev.alvo.pieria.code;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.code.CodeIndexingService.SourceFile;
import dev.alvo.pieria.code.CodeParser.ParseResult;
import dev.alvo.pieria.code.CodeParser.ParsedSymbol;
import dev.alvo.pieria.code.CodeSummarizationService.SummarizationResult;
import dev.alvo.pieria.config.CodeSummarizationProperties;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.model.ModelGateway.CodeSummaryLevel;
import dev.alvo.pieria.storage.SqliteCodeIndexStore;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Network-free integration test for {@link CodeSummarizationService} over the real SQLite
 * substrate: granularity levels, evidence assembly (child summaries feed parent prompts),
 * content-addressed skipping, supersession on change, and failure isolation. The deterministic
 * {@link FakeModelGateway} stands in for the synthesis model.
 */
class CodeSummarizationServiceTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore memoryStore;
  private CodeIndexingService indexing;
  private FakeModelGateway gateway;
  private String profileId;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-codesum-test-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();

    JdbcClient jdbc = JdbcClient.create(dataSource);
    memoryStore = new SqliteMemoryStore(jdbc);
    SqliteCodeIndexStore codeStore = new SqliteCodeIndexStore(jdbc);

    // Two modules (marked by build files) with one parsed source file each.
    FakeCodeParser parser = new FakeCodeParser("java")
      .register("core/src/A.java", new ParseResult(
        List.of(new ParsedSymbol(CodeSymbolKind.CLASS, "A", "A", "class A", "public", 1, 5, null)),
        List.of()))
      .register("web/src/B.java", new ParseResult(
        List.of(new ParsedSymbol(CodeSymbolKind.CLASS, "B", "B", "class B", "public", 1, 5, null)),
        List.of()));

    indexing = new CodeIndexingService(memoryStore, codeStore, List.of(parser),
      new DataSourceTransactionManager(dataSource));
    gateway = new FakeModelGateway();
    profileId = memoryStore.getOrCreateProfile("code").id();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (dataSource != null) {
      dataSource.close();
    }
    if (dbFile != null) {
      Files.deleteIfExists(dbFile);
      Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
      Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
    }
  }

  private static List<SourceFile> batch(String aContent, String bContent) {
    return List.of(
      new SourceFile("core/build.gradle", null, null, "plugins {}"),
      new SourceFile("core/src/A.java", "java", null, aContent),
      new SourceFile("web/build.gradle", null, null, "plugins {}"),
      new SourceFile("web/src/B.java", "java", null, bContent));
  }

  private CodeSummarizationService service(String granularity) {
    return new CodeSummarizationService(memoryStore, gateway,
      new CodeSummarizationProperties(true, granularity, 20000, 80, 40));
  }

  private SummarizationResult run(String granularity, List<SourceFile> files) {
    indexing.index("code", "t", files);
    return service(granularity).summarize("code", files, IngestProgressListener.noop());
  }

  private List<Memory> active(String topicKey) {
    return memoryStore.findActiveByTopicKey(profileId, MemoryType.FACT, topicKey);
  }

  @Test
  void moduleGranularityStoresModuleAndArchitectureSummaries() {
    SummarizationResult r = run("module", batch("class A {}", "class B {}"));

    assertThat(r.stored()).isEqualTo(3); // core, web, architecture
    assertThat(r.failed()).isZero();
    assertThat(active(CodeSummarizationService.TOPIC_MODULE_PREFIX + "core")).hasSize(1);
    assertThat(active(CodeSummarizationService.TOPIC_MODULE_PREFIX + "web")).hasSize(1);
    assertThat(active(CodeSummarizationService.TOPIC_ARCHITECTURE)).hasSize(1);
    assertThat(active(CodeSummarizationService.TOPIC_FILE_PREFIX + "core/src/A.java")).isEmpty();

    Memory arch = active(CodeSummarizationService.TOPIC_ARCHITECTURE).getFirst();
    assertThat(arch.sessionId()).isEqualTo(CodeIndexingService.CODE_SESSION);
    assertThat(arch.payload()).contains("\"source\":\"code-summary\"").contains("\"level\":\"architecture\"");

    // Module summaries were written before the architecture prompt and fed into it.
    var archInput = gateway.summarizeCalls.stream()
      .filter(c -> c.level() == CodeSummaryLevel.ARCHITECTURE).findFirst().orElseThrow();
    assertThat(archInput.childSummaries()).hasSize(2);
    assertThat(archInput.childSummaries().getFirst()).startsWith("summary[module:");
  }

  @Test
  void fileGranularityAddsFileSummariesAndFeedsThemIntoModulePrompts() {
    SummarizationResult r = run("file", batch("class A {}", "class B {}"));

    assertThat(r.stored()).isEqualTo(7); // 4 files + 2 modules + architecture
    assertThat(active(CodeSummarizationService.TOPIC_FILE_PREFIX + "core/src/A.java")).hasSize(1);

    var coreModuleInput = gateway.summarizeCalls.stream()
      .filter(c -> c.level() == CodeSummaryLevel.MODULE && c.subjectPath().equals("core"))
      .findFirst().orElseThrow();
    assertThat(coreModuleInput.childSummaries()).isNotEmpty();
    assertThat(coreModuleInput.childSummaries().getFirst()).startsWith("summary[file:core/");
    // The module prompt also carries the deterministic symbol outline of the parsed member.
    assertThat(coreModuleInput.outlines().stream().anyMatch(o -> o.contains("class A"))).isTrue();
  }

  @Test
  void architectureGranularityStoresExactlyOneSummaryFromModuleListings() {
    SummarizationResult r = run("architecture", batch("class A {}", "class B {}"));

    assertThat(r.stored()).isEqualTo(1);
    assertThat(active(CodeSummarizationService.TOPIC_ARCHITECTURE)).hasSize(1);
    var archInput = gateway.summarizeCalls.getFirst();
    assertThat(archInput.childSummaries()).isEmpty();
    assertThat(archInput.outlines()).anySatisfy(o -> assertThat(o).startsWith("core: "));
  }

  @Test
  void unchangedRunSkipsEverythingWithoutModelCalls() {
    List<SourceFile> files = batch("class A {}", "class B {}");
    run("module", files);
    gateway.summarizeCalls.clear();

    SummarizationResult second = service("module").summarize("code", files, IngestProgressListener.noop());

    assertThat(second.stored()).isZero();
    assertThat(second.skippedUnchanged()).isEqualTo(3);
    assertThat(gateway.summarizeCalls).isEmpty();
  }

  @Test
  void changedFileRegeneratesOnlyItsModuleAndArchitecture() {
    run("module", batch("class A {}", "class B {}"));
    gateway.summarizeCalls.clear();

    SummarizationResult second = run("module", batch("class A { void x() {} }", "class B {}"));

    assertThat(second.stored()).isEqualTo(2);          // core module + architecture
    assertThat(second.skippedUnchanged()).isEqualTo(1); // web module untouched
    assertThat(gateway.summarizeCalls)
      .extracting(c -> c.level() + ":" + c.subjectPath())
      .containsExactly("MODULE:core", "ARCHITECTURE:code");

    // The stale core summary was superseded: exactly one active row remains for its key.
    assertThat(active(CodeSummarizationService.TOPIC_MODULE_PREFIX + "core")).hasSize(1);
    assertThat(memoryStore.listMemories(profileId, MemoryType.FACT, null).stream()
      .filter(m -> (CodeSummarizationService.TOPIC_MODULE_PREFIX + "core").equals(m.topicKey()))
      .count()).isEqualTo(1); // listMemories returns active only
  }

  @Test
  void summaryFailuresAreCountedAndNeverThrow() {
    List<SourceFile> files = batch("class A {}", "class B {}");
    indexing.index("code", "t", files);
    gateway.setFailSummaries(true);

    SummarizationResult r = service("module").summarize("code", files, IngestProgressListener.noop());

    assertThat(r.stored()).isZero();
    assertThat(r.failed()).isEqualTo(3);
    assertThat(memoryStore.listMemories(profileId, MemoryType.FACT, null).stream()
      .noneMatch(m -> m.topicKey() != null && m.topicKey().startsWith("code:summary:"))).isTrue();
  }

  @Test
  void unavailableModelIsIsolatedPerTarget() {
    List<SourceFile> files = batch("class A {}", "class B {}");
    indexing.index("code", "t", files);
    gateway.setUnavailable(true);

    SummarizationResult r = service("module").summarize("code", files, IngestProgressListener.noop());

    assertThat(r.failed()).isEqualTo(3);
  }

  @Test
  void emptyBatchIsANoOp() {
    SummarizationResult r = service("module").summarize("code", List.of(), IngestProgressListener.noop());
    assertThat(r).isEqualTo(SummarizationResult.empty());
    assertThat(gateway.summarizeCalls).isEmpty();
  }
}
