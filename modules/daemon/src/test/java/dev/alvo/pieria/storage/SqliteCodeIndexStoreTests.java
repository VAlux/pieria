package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.domain.code.CodeEdge;
import dev.alvo.pieria.domain.code.CodeFile;
import dev.alvo.pieria.domain.code.CodeRelation;
import dev.alvo.pieria.domain.code.CodeSymbol;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import dev.alvo.pieria.domain.profile.Profile;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Network-free integration test for {@link SqliteCodeIndexStore}: V5 migration, idempotent
 * upserts, atomic per-file replace, symbol FTS, name/id lookups, and confidence-bounded neighborhood
 * traversal.
 */
class SqliteCodeIndexStoreTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteCodeIndexStore store;
  private String profileId;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-code-test-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();

    JdbcClient jdbc = JdbcClient.create(dataSource);
    store = new SqliteCodeIndexStore(jdbc);
    Profile p = new SqliteMemoryStore(jdbc).getOrCreateProfile("code");
    profileId = p.id();
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

  private CodeFile indexFile(String path, String hash, List<CodeSymbol> symbols, List<CodeEdge> edges) {
    CodeFile file = CodeFile.of("java", path, hash, symbols.size(), null);
    store.replaceFileIndex(profileId, file, symbols, edges);
    return store.upsertCodeFile(profileId, file); // returns the stored row with its id
  }

  private static CodeSymbol method(String name, String fqn) {
    return CodeSymbol.of(CodeSymbolKind.METHOD, name, fqn, name + "()", "public", 1, 2, "java", null);
  }

  @Test
  void migrationCreatesCodeTablesAndIndexIsInitiallyAbsent() {
    assertThat(store.isCodeIndexPresent(profileId)).isFalse();
    assertThat(store.counts(profileId).files()).isZero();
  }

  @Test
  void upsertsAreIdempotentOnContentAddressedIds() {
    CodeSymbol s = method("create", "com.x.Bar#create");
    CodeFile file = store.upsertCodeFile(profileId, CodeFile.of("java", "Bar.java", "h1", 10, null));
    CodeSymbol stored = store.upsertCodeSymbol(profileId,
      new CodeSymbol(null, profileId, file.id(), s.kind(), s.name(), s.qualifiedName(), s.signature(),
        s.visibility(), 1, 2, "java", null, "Bar.java"));
    CodeSymbol again = store.upsertCodeSymbol(profileId,
      new CodeSymbol(null, profileId, file.id(), s.kind(), s.name(), s.qualifiedName(), s.signature(),
        s.visibility(), 1, 2, "java", null, "Bar.java"));

    assertThat(again.id()).isEqualTo(stored.id());
    assertThat(store.counts(profileId).symbols()).isEqualTo(1);
  }

  @Test
  void replaceFileIndexSwapsOnlyThatFilesSymbolsAndEdges() {
    CodeSymbol a = method("alpha", "Bar#alpha");
    CodeSymbol b = method("beta", "Bar#beta");
    CodeFile bar = indexFile("Bar.java", "h1", List.of(a, b), List.of());

    CodeSymbol other = method("gamma", "Foo#gamma");
    indexFile("Foo.java", "h1", List.of(other), List.of());
    assertThat(store.counts(profileId).symbols()).isEqualTo(3);

    // Re-index Bar.java with a different content: its two symbols are replaced by one; Foo untouched.
    CodeSymbol a2 = method("alpha", "Bar#alpha");
    indexFile("Bar.java", "h2", List.of(a2), List.of());

    assertThat(store.counts(profileId).symbols()).isEqualTo(2); // 1 (Bar) + 1 (Foo)
    assertThat(store.fileContentHash(profileId, "Bar.java")).contains("h2");
    assertThat(store.findSymbolsByName(profileId, List.of("beta"), 10)).isEmpty();
    assertThat(store.findSymbolsByName(profileId, List.of("gamma"), 10)).hasSize(1);
    assertThat(store.counts(profileId).files()).isEqualTo(2);
  }

  @Test
  void symbolFtsMatchesNameAndQualifiedName() {
    CodeFile file = store.upsertCodeFile(profileId, CodeFile.of("java", "Bar.java", "h1", 10, null));
    store.upsertCodeSymbol(profileId,
      new CodeSymbol(null, profileId, file.id(), CodeSymbolKind.METHOD, "createUser",
        "com.x.UserService#createUser", "createUser(req)", "public", 1, 5, "java", null, "Bar.java"));

    assertThat(store.searchSymbolsFts(profileId, "createUser", 10)).hasSize(1);
    assertThat(store.searchSymbolsFts(profileId, "UserService", 10)).hasSize(1);
    assertThat(store.searchSymbolsFts(profileId, "  ", 10)).isEmpty();
    assertThat(store.searchSymbolsFts(profileId, "nonexistent", 10)).isEmpty();
  }

  @Test
  void findSymbolsByIdsPreservesRequestedOrder() {
    CodeFile file = store.upsertCodeFile(profileId, CodeFile.of("java", "Bar.java", "h1", 10, null));
    CodeSymbol s1 = store.upsertCodeSymbol(profileId, named(file.id(), "a", "Bar#a"));
    CodeSymbol s2 = store.upsertCodeSymbol(profileId, named(file.id(), "b", "Bar#b"));

    List<CodeSymbol> ordered = store.findSymbolsByIds(profileId, List.of(s2.id(), s1.id()), 10);
    assertThat(ordered).extracting(CodeSymbol::id).containsExactly(s2.id(), s1.id());
  }

  @Test
  void neighborhoodHonorsDepthAndMinConfidence() {
    CodeFile file = store.upsertCodeFile(profileId, CodeFile.of("java", "G.java", "h1", 10, null));
    CodeSymbol sa = store.upsertCodeSymbol(profileId, named(file.id(), "a", "G#a"));
    CodeSymbol sb = store.upsertCodeSymbol(profileId, named(file.id(), "b", "G#b"));
    CodeSymbol sc = store.upsertCodeSymbol(profileId, named(file.id(), "c", "G#c"));

    store.upsertCodeEdge(profileId, new CodeEdge(null, profileId, sa.id(),
      CodeRelation.CALLS, EdgeConfidence.RESOLVED, sb.id(), "b", file.id()));
    store.upsertCodeEdge(profileId, new CodeEdge(null, profileId, sb.id(),
      CodeRelation.CALLS, EdgeConfidence.HEURISTIC, sc.id(), "c", file.id()));

    // Including heuristic edges, depth 2 reaches a → b → c.
    assertThat(store.symbolNeighborhood(profileId, List.of(sa.id()), 2, 20, EdgeConfidence.HEURISTIC))
      .containsExactlyInAnyOrder(sa.id(), sb.id(), sc.id());
    // Resolved-only stops at b (the b→c edge is heuristic).
    assertThat(store.symbolNeighborhood(profileId, List.of(sa.id()), 2, 20, EdgeConfidence.RESOLVED))
      .containsExactlyInAnyOrder(sa.id(), sb.id());
    // Depth 1 only reaches the direct neighbor.
    assertThat(store.symbolNeighborhood(profileId, List.of(sa.id()), 1, 20, EdgeConfidence.HEURISTIC))
      .containsExactlyInAnyOrder(sa.id(), sb.id());
  }

  @Test
  void countsSplitResolvedAndHeuristicEdges() {
    CodeFile file = store.upsertCodeFile(profileId, CodeFile.of("java", "G.java", "h1", 10, null));
    CodeSymbol sa = store.upsertCodeSymbol(profileId, named(file.id(), "a", "G#a"));
    CodeSymbol sb = store.upsertCodeSymbol(profileId, named(file.id(), "b", "G#b"));
    store.upsertCodeEdge(profileId, new CodeEdge(null, profileId, sa.id(),
      CodeRelation.CALLS, EdgeConfidence.RESOLVED, sb.id(), "b", file.id()));
    store.upsertCodeEdge(profileId, new CodeEdge(null, profileId, sa.id(),
      CodeRelation.REFERENCES, EdgeConfidence.HEURISTIC, null, "Unknown", file.id()));

    CodeIndexStore.CodeIndexCounts counts = store.counts(profileId);
    assertThat(counts.resolvedEdges()).isEqualTo(1);
    assertThat(counts.heuristicEdges()).isEqualTo(1);
    assertThat(counts.edges()).isEqualTo(2);
    assertThat(store.isCodeIndexPresent(profileId)).isTrue();
  }

  @Test
  void findEdgesTouchingMatchesBySourceAndByTarget() {
    CodeFile file = store.upsertCodeFile(profileId, CodeFile.of("java", "G.java", "h1", 10, null));
    CodeSymbol sa = store.upsertCodeSymbol(profileId, named(file.id(), "a", "G#a"));
    CodeSymbol sb = store.upsertCodeSymbol(profileId, named(file.id(), "b", "G#b"));
    store.upsertCodeEdge(profileId, new CodeEdge(null, profileId, sa.id(),
      CodeRelation.CALLS, EdgeConfidence.RESOLVED, sb.id(), "b", file.id()));

    for (String seed : List.of(sa.id(), sb.id())) {
      List<CodeIndexStore.EdgeEvidence> hits =
        store.findEdgesTouching(profileId, List.of(seed), EdgeConfidence.HEURISTIC, 10);
      assertThat(hits).hasSize(1);
      assertThat(hits.getFirst().src().qualifiedName()).isEqualTo("G#a");
      assertThat(hits.getFirst().dst().qualifiedName()).isEqualTo("G#b");
      assertThat(hits.getFirst().edge().relation()).isEqualTo(CodeRelation.CALLS);
    }
  }

  @Test
  void findEdgesTouchingReturnsUnresolvedTargetAsNullDst() {
    CodeFile file = store.upsertCodeFile(profileId, CodeFile.of("java", "G.java", "h1", 10, null));
    CodeSymbol sa = store.upsertCodeSymbol(profileId, named(file.id(), "a", "G#a"));
    store.upsertCodeEdge(profileId, new CodeEdge(null, profileId, sa.id(),
      CodeRelation.REFERENCES, EdgeConfidence.HEURISTIC, null, "Unknown", file.id()));

    List<CodeIndexStore.EdgeEvidence> hits =
      store.findEdgesTouching(profileId, List.of(sa.id()), EdgeConfidence.HEURISTIC, 10);
    assertThat(hits).hasSize(1);
    assertThat(hits.getFirst().dst()).isNull();
    assertThat(hits.getFirst().edge().dstRef()).isEqualTo("Unknown");
  }

  @Test
  void findEdgesTouchingHonorsMinConfidenceLimitAndOrder() {
    CodeFile file = store.upsertCodeFile(profileId, CodeFile.of("java", "G.java", "h1", 10, null));
    CodeSymbol sa = store.upsertCodeSymbol(profileId, named(file.id(), "a", "G#a"));
    CodeSymbol sb = store.upsertCodeSymbol(profileId, named(file.id(), "b", "G#b"));
    store.upsertCodeEdge(profileId, new CodeEdge(null, profileId, sa.id(),
      CodeRelation.REFERENCES, EdgeConfidence.HEURISTIC, null, "Unknown", file.id()));
    store.upsertCodeEdge(profileId, new CodeEdge(null, profileId, sa.id(),
      CodeRelation.CALLS, EdgeConfidence.RESOLVED, sb.id(), "b", file.id()));

    // Resolved-only filters the heuristic edge out.
    assertThat(store.findEdgesTouching(profileId, List.of(sa.id()), EdgeConfidence.RESOLVED, 10))
      .hasSize(1)
      .allSatisfy(e -> assertThat(e.edge().confidence()).isEqualTo(EdgeConfidence.RESOLVED));

    // Both allowed: resolved sorts first; limit 1 keeps only it.
    List<CodeIndexStore.EdgeEvidence> all =
      store.findEdgesTouching(profileId, List.of(sa.id()), EdgeConfidence.HEURISTIC, 10);
    assertThat(all).hasSize(2);
    assertThat(all.getFirst().edge().confidence()).isEqualTo(EdgeConfidence.RESOLVED);
    assertThat(store.findEdgesTouching(profileId, List.of(sa.id()), EdgeConfidence.HEURISTIC, 1))
      .hasSize(1)
      .allSatisfy(e -> assertThat(e.edge().confidence()).isEqualTo(EdgeConfidence.RESOLVED));
  }

  @Test
  void findEdgesTouchingIsEmptyOnEmptyIdsOrZeroLimit() {
    assertThat(store.findEdgesTouching(profileId, List.of(), EdgeConfidence.HEURISTIC, 10)).isEmpty();
    assertThat(store.findEdgesTouching(profileId, List.of("x"), EdgeConfidence.HEURISTIC, 0)).isEmpty();
  }

  private static CodeSymbol named(String fileId, String name, String fqn) {
    return new CodeSymbol(null, null, fileId, CodeSymbolKind.METHOD, name, fqn, name + "()",
      "public", 1, 2, "java", null, "G.java");
  }
}
