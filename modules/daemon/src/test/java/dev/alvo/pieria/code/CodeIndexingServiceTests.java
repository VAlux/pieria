package dev.alvo.pieria.code;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.code.CodeIndexingService.CodeIndexSummary;
import dev.alvo.pieria.code.CodeIndexingService.SourceFile;
import dev.alvo.pieria.code.CodeParser.ParseResult;
import dev.alvo.pieria.code.CodeParser.ParsedEdge;
import dev.alvo.pieria.code.CodeParser.ParsedSymbol;
import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.domain.code.CodeRelation;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.profile.Profile;
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
 * Network-free integration test for {@link CodeIndexingService}: parse → substrate → derived fact
 * with symbol-id provenance → graph projection, plus skip-if-unchanged and supersession.
 */
class CodeIndexingServiceTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;
  private SqliteMemoryStore memoryStore;
  private SqliteCodeIndexStore codeStore;
  private CodeIndexingService service;
  private FakeCodeParser parser;
  private String profileId;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-codeidx-test-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();

    jdbc = JdbcClient.create(dataSource);
    memoryStore = new SqliteMemoryStore(jdbc);
    codeStore = new SqliteCodeIndexStore(jdbc);

    parser = new FakeCodeParser("java")
      .register("Bar.java", new ParseResult(
        List.of(
          new ParsedSymbol(CodeSymbolKind.CLASS, "Bar", "Bar", "class Bar", "public", 1, 20, null),
          new ParsedSymbol(CodeSymbolKind.METHOD, "create", "Bar#create", "create()", "public", 5, 9, "Bar")),
        List.of(new ParsedEdge("Bar", CodeRelation.DEPENDS_ON, EdgeConfidence.HEURISTIC, null, "othermodule"))));

    service = new CodeIndexingService(memoryStore, codeStore, List.of(parser),
      new DataSourceTransactionManager(dataSource));
    Profile p = memoryStore.getOrCreateProfile("code");
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

  private SourceFile bar(String hash, String content) {
    return new SourceFile("Bar.java", "java", hash, content);
  }

  @Test
  void indexesSymbolsAndDerivesFileFactWithProvenance() {
    CodeIndexSummary s = service.index("code", "tree1", List.of(bar("h1", "class Bar {}")));

    assertThat(s.filesParsed()).isEqualTo(1);
    assertThat(s.symbols()).isEqualTo(2);
    assertThat(s.memoriesStored()).isEqualTo(1);
    assertThat(codeStore.counts(profileId).symbols()).isEqualTo(2);

    var storedSymbols = codeStore.findSymbolsByName(profileId, List.of("Bar", "create"), 10);
    var barSymbol = storedSymbols.stream().filter(symbol -> symbol.name().equals("Bar")).findFirst().orElseThrow();
    assertThat(storedSymbols).filteredOn(symbol -> symbol.name().equals("create"))
      .singleElement().extracting(dev.alvo.pieria.domain.code.CodeSymbol::parentSymbolId)
      .isEqualTo(barSymbol.id());

    List<Memory> facts = memoryStore.listMemories(profileId, MemoryType.FACT, null);
    assertThat(facts).hasSize(1);
    Memory fact = facts.getFirst();
    assertThat(fact.topicKey()).isEqualTo("code:file:Bar.java");
    assertThat(fact.content()).contains("class Bar", "method create");
    assertThat(fact.payload()).contains("\"source\":\"code\"").contains("\"symbolIds\"");

    // Provenance: the fact is reachable from its source symbol ids.
    List<String> symbolIds = codeStore.findSymbolsByName(profileId, List.of("Bar", "create"), 10)
      .stream().map(sym -> sym.id()).toList();
    assertThat(memoryStore.findCodeMemoriesBySymbolIds(profileId, symbolIds, 10))
      .extracting(Memory::id).contains(fact.id());
  }

  @Test
  void graphProjectionReusesEntityEdgeAndCoRetrieves() {
    Memory fact = service.index("code", "t", List.of(bar("h1", "class Bar {}"))) != null
      ? memoryStore.listMemories(profileId, MemoryType.FACT, null).getFirst()
      : null;

    // The depends-on target entity should co-retrieve the file fact via the graph surface.
    String moduleEntityId = ContentId.forEntity(profileId, "module", "othermodule");
    assertThat(memoryStore.findMemoriesByEntities(profileId, List.of(moduleEntityId), 10))
      .extracting(Memory::id)
      .contains(fact.id());
  }

  @Test
  void unchangedReindexIsSkipped() {
    service.index("code", "t", List.of(bar("h1", "class Bar {}")));
    CodeIndexSummary second = service.index("code", "t", List.of(bar("h1", "class Bar {}")));

    assertThat(second.filesSkippedUnchanged()).isEqualTo(1);
    assertThat(second.memoriesStored()).isZero();
    assertThat(memoryStore.listMemories(profileId, MemoryType.FACT, null)).hasSize(1);
  }

  @Test
  void identicalCodeCanBeIndexedIntoTwoProfilesWithIndependentMemories() {
    service.index("code", "t", List.of(bar("h1", "class Bar {}")));
    Profile copy = memoryStore.getOrCreateProfile("code-copy");

    CodeIndexSummary copied = service.index("code-copy", "t", List.of(bar("h1", "class Bar {}")));

    assertThat(copied.memoriesStored()).isEqualTo(1);
    Memory originalFact = memoryStore.listMemories(profileId, MemoryType.FACT, null).getFirst();
    Memory copiedFact = memoryStore.listMemories(copy.id(), MemoryType.FACT, null).getFirst();
    assertThat(copiedFact.id()).isNotEqualTo(originalFact.id());

    List<String> copiedSymbolIds = codeStore.findSymbolsByName(copy.id(), List.of("Bar", "create"), 10)
      .stream().map(symbol -> symbol.id()).toList();
    assertThat(memoryStore.findCodeMemoriesBySymbolIds(copy.id(), copiedSymbolIds, 10))
      .extracting(Memory::id)
      .containsExactly(copiedFact.id());
  }

  @Test
  void unchangedIndexRepairsMissingDerivedMemory() {
    service.index("code", "t", List.of(bar("h1", "class Bar {}")));
    Memory fact = memoryStore.listMemories(profileId, MemoryType.FACT, null).getFirst();
    jdbc.sql("DELETE FROM edges WHERE memory_id = ?").param(fact.id()).update();
    jdbc.sql("DELETE FROM vectorization_outbox WHERE memory_id = ?").param(fact.id()).update();
    jdbc.sql("DELETE FROM memories WHERE id = ?").param(fact.id()).update();

    CodeIndexSummary repaired = service.index("code", "t", List.of(bar("h1", "class Bar {}")));

    assertThat(repaired.filesParsed()).isEqualTo(1);
    assertThat(repaired.filesSkippedUnchanged()).isZero();
    assertThat(repaired.memoriesStored()).isEqualTo(1);
    assertThat(memoryStore.listMemories(profileId, MemoryType.FACT, null))
      .singleElement()
      .extracting(Memory::topicKey)
      .isEqualTo("code:file:Bar.java");

    CodeIndexSummary stable = service.index("code", "t", List.of(bar("h1", "class Bar {}")));
    assertThat(stable.filesSkippedUnchanged()).isEqualTo(1);
    assertThat(stable.memoriesStored()).isZero();
  }

  @Test
  void derivedFactIsGraphAdoptedSoItNeverReachesTheModel() {
    service.index("code", "t", List.of(bar("h1", "class Bar {}")));
    Memory fact = memoryStore.listMemories(profileId, MemoryType.FACT, null).getFirst();

    assertThat(memoryStore.isGraphAdopted(profileId, fact.id())).isTrue();
    // The graph-orphan sweep is what would otherwise pay a model call for this template text.
    assertThat(memoryStore.countGraphOrphans(profileId)).isZero();
    assertThat(memoryStore.countGraphOrphans(profileId, List.of(CodeIndexingService.CODE_SESSION))).isZero();
  }

  @Test
  void projectsTopLevelDefinitionsButNotMembers() {
    service.index("code", "t", List.of(bar("h1", "class Bar {}")));
    Memory fact = memoryStore.listMemories(profileId, MemoryType.FACT, null).getFirst();

    // The top-level class becomes a node reachable from the file fact...
    String classEntityId = ContentId.forEntity(profileId, "class", "bar");
    assertThat(memoryStore.findMemoriesByEntities(profileId, List.of(classEntityId), 10))
      .extracting(Memory::id)
      .contains(fact.id());
    // ...while its member method does not: members would flood the graph without adding reach.
    String methodEntityId = ContentId.forEntity(profileId, "method", "create");
    assertThat(memoryStore.findMemoriesByEntities(profileId, List.of(methodEntityId), 10)).isEmpty();
  }

  @Test
  void unchangedIndexRepairsAFactStoredBeforeDeterministicProjection() {
    service.index("code", "t", List.of(bar("h1", "class Bar {}")));
    Memory fact = memoryStore.listMemories(profileId, MemoryType.FACT, null).getFirst();
    // Reproduce a row written by an older build: edges projected, but never stamped as settled.
    jdbc.sql("UPDATE memories SET graph_adopted_at = NULL WHERE id = ?").param(fact.id()).update();
    jdbc.sql("DELETE FROM edges WHERE memory_id = ?").param(fact.id()).update();
    assertThat(memoryStore.countGraphOrphans(profileId)).isEqualTo(1L);

    CodeIndexSummary repaired = service.index("code", "t", List.of(bar("h1", "class Bar {}")));

    assertThat(repaired.filesSkippedUnchanged()).isZero();
    assertThat(repaired.graphEdges()).isPositive();
    assertThat(memoryStore.countGraphOrphans(profileId)).isZero();

    // And the repair settles: the next pass skips again rather than re-projecting forever.
    assertThat(service.index("code", "t", List.of(bar("h1", "class Bar {}"))).filesSkippedUnchanged())
      .isEqualTo(1);
  }

  @Test
  void changedFileSupersedesPriorFactWhenSummaryChanges() {
    service.index("code", "t", List.of(bar("h1", "class Bar {}")));

    // A summary-changing edit (a real parser would surface the new method).
    parser.register("Bar.java", new ParseResult(
      List.of(
        new ParsedSymbol(CodeSymbolKind.CLASS, "Bar", "Bar", "class Bar", "public", 1, 25, null),
        new ParsedSymbol(CodeSymbolKind.METHOD, "create", "Bar#create", "create()", "public", 5, 9, "Bar"),
        new ParsedSymbol(CodeSymbolKind.METHOD, "delete", "Bar#delete", "delete()", "public", 11, 14, "Bar")),
      List.of()));
    CodeIndexSummary second = service.index("code", "t", List.of(bar("h2", "class Bar { changed }")));

    assertThat(second.memoriesSuperseded()).isEqualTo(1);
    // Only one active fact remains for the file's topic key.
    assertThat(memoryStore.listMemories(profileId, MemoryType.FACT, null)).hasSize(1);
  }

  @Test
  void unknownLanguageIndexesFileWithoutSymbolsOrFact() {
    CodeIndexSummary s = service.index("code", "t",
      List.of(new SourceFile("notes.txt", null, "h1", "just text")));

    assertThat(s.symbols()).isZero();
    assertThat(s.memoriesStored()).isZero();
    assertThat(codeStore.counts(profileId).files()).isEqualTo(1); // file row still created
  }
}
