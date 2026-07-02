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
import dev.alvo.pieria.retrieval.RetrievalChannel;
import dev.alvo.pieria.retrieval.RetrievalContext;
import dev.alvo.pieria.retrieval.model.GraphEvidence;
import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
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

    FakeCodeParser parser = new FakeCodeParser("java")
      .register("Bar.java", new ParseResult(
        List.of(
          new ParsedSymbol(CodeSymbolKind.CLASS, "Bar", "Bar", "class Bar", "public", 1, 30, null),
          new ParsedSymbol(CodeSymbolKind.METHOD, "createUser", "Bar#createUser", "createUser()", "public", 5, 9, "Bar"),
          new ParsedSymbol(CodeSymbolKind.METHOD, "helper", "Bar#helper", "helper()", "private", 11, 14, "Bar")),
        List.of(new ParsedEdge("Bar#createUser", CodeRelation.CALLS, EdgeConfidence.RESOLVED, "Bar#helper", "helper"))))
      .register("Baz.java", new ParseResult(
        List.of(
          new ParsedSymbol(CodeSymbolKind.CLASS, "Baz", "Baz", "class Baz", "public", 1, 30, null),
          new ParsedSymbol(CodeSymbolKind.METHOD, "work", "Baz#work", "work()", "public", 5, 9, "Baz"),
          new ParsedSymbol(CodeSymbolKind.METHOD, "util", "Baz#util", "util()", "private", 11, 14, "Baz")),
        List.of(new ParsedEdge("Baz#work", CodeRelation.CALLS, EdgeConfidence.RESOLVED, "Baz#util", "util"))));

    CodeIndexingService service = new CodeIndexingService(memoryStore, codeStore, List.of(parser),
      new DataSourceTransactionManager(dataSource));
    profileId = memoryStore.getOrCreateProfile("code").id();
    service.index("code", "t", List.of(
      new SourceFile("Bar.java", "java", "h1", "class Bar {}"),
      new SourceFile("Baz.java", "java", "h2", "class Baz {}")));
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

  @Test
  void codeGraphEmitsEdgeEvidenceForSeedSymbols() {
    CodeGraphChannel channel = new CodeGraphChannel(memoryStore, codeStore, 2, 20, 8, EdgeConfidence.HEURISTIC);
    RetrievalChannel.ChannelResult out = channel.retrieveWithEvidence(ctx(List.of(), List.of("createUser"), List.of()));

    assertThat(out.candidates()).extracting(c -> c.memory().topicKey()).contains("code:file:Bar.java");
    assertThat(out.evidence()).hasSize(1);
    GraphEvidence evidence = out.evidence().getFirst();
    assertThat(evidence.src()).isEqualTo("Bar#createUser");
    assertThat(evidence.relation()).isEqualTo("calls");
    assertThat(evidence.dst()).isEqualTo("Bar#helper");
    assertThat(evidence.confidence()).isEqualTo("resolved");
    assertThat(evidence.render())
      .isEqualTo("Bar#createUser (Bar.java) calls Bar#helper (Bar.java) [resolved]");
  }

  @Test
  void evidenceComesFromQueryNamedSymbolsOnlyWhenTheQueryNamesAny() {
    CodeGraphChannel channel = new CodeGraphChannel(memoryStore, codeStore, 2, 20, 8, EdgeConfidence.HEURISTIC);
    // Wave-1 provenance carries Baz.java's symbols; the query names createUser. Evidence must not
    // be flooded by the provenance file's internal calls.
    RetrievalChannel.ChannelResult out =
      channel.retrieveWithEvidence(ctx(List.of(), List.of("createUser"), List.of(bazCandidate())));

    assertThat(out.evidence()).hasSize(1);
    assertThat(out.evidence().getFirst().src()).isEqualTo("Bar#createUser");
  }

  @Test
  void evidenceFallsBackToProvenanceSeedsWhenTheQueryNamesNoSymbol() {
    CodeGraphChannel channel = new CodeGraphChannel(memoryStore, codeStore, 2, 20, 8, EdgeConfidence.HEURISTIC);
    RetrievalChannel.ChannelResult out =
      channel.retrieveWithEvidence(ctx(List.of(), List.of(), List.of(bazCandidate())));

    assertThat(out.evidence()).hasSize(1);
    assertThat(out.evidence().getFirst().src()).isEqualTo("Baz#work");
  }

  /** Baz.java's derived code memory wrapped as a wave-1 candidate (carries its symbolIds payload). */
  private RetrievalCandidate bazCandidate() {
    Memory baz = memoryStore.exactKeyLookup(profileId, List.of("code:file:Baz.java"), 1).getFirst();
    return new RetrievalCandidate(baz, RetrievalChannelType.FTS_MEMORY, 1, baz.content());
  }

  @Test
  void codeGraphEvidenceIsEmptyWhenNoCodeIndexForProfile() {
    String emptyId = memoryStore.getOrCreateProfile("empty").id();
    CodeGraphChannel channel = new CodeGraphChannel(memoryStore, codeStore, 2, 20, 8, EdgeConfidence.HEURISTIC);
    RetrievalContext emptyCtx = new RetrievalContext(emptyId, "q",
      new QueryAnalysis(List.of(), List.of(), List.of("createUser"), null), null, null, 10, List.of());

    RetrievalChannel.ChannelResult out = channel.retrieveWithEvidence(emptyCtx);
    assertThat(out.candidates()).isEmpty();
    assertThat(out.evidence()).isEmpty();
  }
}
