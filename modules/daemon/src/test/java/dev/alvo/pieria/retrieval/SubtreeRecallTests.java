package dev.alvo.pieria.retrieval;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.config.VerifyMode;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.storage.NoOpCodeIndexStore;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recall over a profile tree, end to end against a real SQLite store: a profile's recall also reads
 * every profile below it and names each memory's owner, while a child's recall, and a recall on a
 * profile without children, read only that profile and name no owner.
 */
class SubtreeRecallTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore store;
  private RetrievalService service;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-subtree-recall-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    Flyway.configure().dataSource(dataSource).load().migrate();
    store = new SqliteMemoryStore(JdbcClient.create(dataSource));
    PieriaProperties.Retrieval retrieval = new PieriaProperties.Retrieval(true, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2,
      20, 8, 10, 3000, 0.0, 0.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60, 0.78,
      false, 0.4, true, 30, 400, 4000L, 1.0);
    PieriaProperties props = new PieriaProperties(null, null, null, null,
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS,
        1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70), retrieval, null);
    service = new RetrievalService(store, new FakeModelGateway(), new DeterministicQueryAnalyzer(),
      new NoOpCodeIndexStore(), EffectiveConfigResolver.withoutOverrides(props), TraceProperties.defaults());
  }

  @AfterEach
  void tearDown() throws Exception {
    dataSource.close();
    Files.deleteIfExists(dbFile);
  }

  private void put(Profile profile, String content, String topicKey) {
    store.insertMemory(profile.id(), Memory.of(MemoryType.FACT, content, "s1", topicKey, null));
  }

  private Map<String, String> recall(String profile, String query) {
    return service.recall(profile, query, 10, false, RecallMode.EVIDENCE, false, null, null)
      .candidates().stream()
      .collect(Collectors.toMap(c -> c.memory().content(), c -> String.valueOf(c.ownerProfile())));
  }

  @Test
  void aParentRecallsItsChildrensMemoriesAndNamesTheirOwners() {
    Profile parent = store.createProfile("parent", null);
    Profile child = store.createProfile("child", parent.id());
    Profile grandchild = store.createProfile("grandchild", child.id());
    Profile sibling = store.createProfile("sibling", null);
    put(parent, "parent builds with gradle", "build.tool");
    put(child, "child builds with maven", "build.tool");
    put(grandchild, "grandchild builds with bazel", "build.tool");
    put(sibling, "sibling builds with make", "build.tool");

    assertThat(recall("parent", "builds")).containsExactlyInAnyOrderEntriesOf(Map.of(
      "parent builds with gradle", "parent",
      "child builds with maven", "child",
      "grandchild builds with bazel", "grandchild"));
    assertThat(recall("child", "builds")).containsExactlyInAnyOrderEntriesOf(Map.of(
      "child builds with maven", "child",
      "grandchild builds with bazel", "grandchild"));
  }

  @Test
  void aProfileWithoutChildrenRecallsAsBeforeWithNoOwners() {
    Profile parent = store.createProfile("parent", null);
    Profile leaf = store.createProfile("leaf", parent.id());
    put(parent, "parent builds with gradle", null);
    put(leaf, "leaf builds with maven", null);

    assertThat(recall("leaf", "builds")).containsExactlyEntriesOf(Map.of("leaf builds with maven", "null"));
  }

  @Test
  void movingAChildAwayTakesItsMemoriesOutOfTheParentsRecall() {
    Profile parent = store.createProfile("parent", null);
    Profile child = store.createProfile("child", parent.id());
    put(parent, "parent builds with gradle", null);
    put(child, "child builds with maven", null);

    store.setProfileParent(child.id(), null);

    List<String> recalled = List.copyOf(recall("parent", "builds").keySet());
    assertThat(recalled).containsExactly("parent builds with gradle");
  }

  @Test
  void ownersAreNotLostWhenCandidatesAreProjectedForTemporalHistory() {
    Profile parent = store.createProfile("parent", null);
    Profile child = store.createProfile("child", parent.id());
    put(child, "child builds with maven", null);

    List<RecallCandidate> candidates = service.recall("parent", "maven", 10, false, RecallMode.EVIDENCE, false, null, null)
      .candidates();
    assertThat(candidates).extracting(RecallCandidate::ownerProfile).containsExactly("child");
  }
}
