package dev.alvo.pieria.retrieval.channel;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.code.CodeIndexingService;
import dev.alvo.pieria.code.CodeIndexingService.SourceFile;
import dev.alvo.pieria.code.CodeParser.ParseResult;
import dev.alvo.pieria.code.CodeParser.ParsedEdge;
import dev.alvo.pieria.code.CodeParser.ParsedSymbol;
import dev.alvo.pieria.code.FakeCodeParser;
import dev.alvo.pieria.domain.code.CodeRelation;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.retrieval.RetrievalContext;
import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
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
 * Integration tests for {@link SymbolFtsChannel} and {@link CodeGraphChannel} over the real SQLite
 * substrate populated by {@link CodeIndexingService}: a symbol/edge hit resolves back to its derived
 * code memory, and both channels skip cleanly when no code index exists.
 */
class CodeChannelsTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore memoryStore;
  private SqliteCodeIndexStore codeStore;
  private String profileId;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-codechan-test-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();

    JdbcClient jdbc = JdbcClient.create(dataSource);
    memoryStore = new SqliteMemoryStore(jdbc);
    codeStore = new SqliteCodeIndexStore(jdbc);

    FakeCodeParser parser = new FakeCodeParser("java").register("Bar.java", new ParseResult(
      List.of(
        new ParsedSymbol(CodeSymbolKind.CLASS, "Bar", "Bar", "class Bar", "public", 1, 30, null),
        new ParsedSymbol(CodeSymbolKind.METHOD, "createUser", "Bar#createUser", "createUser()", "public", 5, 9, "Bar"),
        new ParsedSymbol(CodeSymbolKind.METHOD, "helper", "Bar#helper", "helper()", "private", 11, 14, "Bar")),
      List.of(new ParsedEdge("Bar#createUser", CodeRelation.CALLS, EdgeConfidence.RESOLVED, "Bar#helper", "helper"))));

    CodeIndexingService service = new CodeIndexingService(memoryStore, codeStore, List.of(parser),
      new DataSourceTransactionManager(dataSource));
    profileId = memoryStore.getOrCreateProfile("code").id();
    service.index("code", "t", List.of(new SourceFile("Bar.java", "java", "h1", "class Bar {}")));
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

  private RetrievalContext ctx(List<String> ftsTerms, List<String> entities, List<RetrievalCandidate> seeds) {
    QueryAnalysis analysis = new QueryAnalysis(List.of(), ftsTerms, entities, null);
    return new RetrievalContext(profileId, "q", analysis, null, null, 10, seeds);
  }

  @Test
  void symbolFtsResolvesSymbolHitToDerivedMemory() {
    SymbolFtsChannel channel = new SymbolFtsChannel(memoryStore, codeStore);
    List<RetrievalCandidate> out = channel.retrieve(ctx(List.of("createUser"), List.of(), List.of()));

    assertThat(out).hasSize(1);
    assertThat(out.getFirst().memory().topicKey()).isEqualTo("code:file:Bar.java");
  }

  @Test
  void symbolFtsSkipsWhenNoCodeIndexForProfile() {
    memoryStore.getOrCreateProfile("empty");
    String emptyId = memoryStore.findProfile("empty").orElseThrow().id();
    SymbolFtsChannel channel = new SymbolFtsChannel(memoryStore, codeStore);
    RetrievalContext emptyCtx = new RetrievalContext(emptyId, "q",
      new QueryAnalysis(List.of(), List.of("createUser"), List.of(), null), null, null, 10, List.of());

    assertThat(channel.retrieve(emptyCtx)).isEmpty();
  }

  @Test
  void codeGraphSeedsFromQueryThenTraversesToDerivedMemory() {
    CodeGraphChannel channel = new CodeGraphChannel(memoryStore, codeStore, 2, 20, 8, EdgeConfidence.HEURISTIC);
    // Seed by the symbol name; the resolved createUser→helper edge is traversed; both symbols belong
    // to the same derived file fact, which is what surfaces.
    List<RetrievalCandidate> out = channel.retrieve(ctx(List.of(), List.of("createUser"), List.of()));

    assertThat(out).extracting(c -> c.memory().topicKey()).contains("code:file:Bar.java");
  }

  @Test
  void codeGraphResolvedOnlyStillTraversesResolvedEdge() {
    CodeGraphChannel channel = new CodeGraphChannel(memoryStore, codeStore, 2, 20, 8, EdgeConfidence.RESOLVED);
    List<RetrievalCandidate> out = channel.retrieve(ctx(List.of(), List.of("createUser"), List.of()));
    assertThat(out).isNotEmpty();
  }

  @Test
  void codeGraphReturnsEmptyWhenNoSeeds() {
    CodeGraphChannel channel = new CodeGraphChannel(memoryStore, codeStore, 2, 20, 8, EdgeConfidence.HEURISTIC);
    assertThat(channel.retrieve(ctx(List.of(), List.of("nosuchsymbol"), List.of()))).isEmpty();
  }
}
