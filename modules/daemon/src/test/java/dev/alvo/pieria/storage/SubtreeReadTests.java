package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.domain.profile.ProfileScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recall reads a bound {@link ProfileScope} widens: each one sees the parent's and the child's
 * memories inside the scope, only the requested profile's outside it, and never a sibling's.
 */
class SubtreeReadTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;
  private SqliteMemoryStore store;
  private SqliteCodeIndexStore codeStore;
  private Profile parent;
  private Profile child;
  private Profile sibling;
  private ProfileScope scope;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-subtree-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = JdbcClient.create(dataSource);
    store = new SqliteMemoryStore(jdbc);
    codeStore = new SqliteCodeIndexStore(jdbc);
    parent = store.createProfile("parent", null);
    child = store.createProfile("child", parent.id());
    sibling = store.createProfile("sibling", null);
    scope = new ProfileScope(parent.id(), store.profileSubtree(parent.id()));
  }

  @AfterEach
  void tearDown() throws Exception {
    dataSource.close();
    Files.deleteIfExists(dbFile);
  }

  private Memory put(Profile profile, String content, String topicKey) {
    return store.insertMemory(profile.id(), Memory.of(MemoryType.FACT, content, "shared-session", topicKey, null));
  }

  private <T> T inScope(Supplier<T> read) {
    return store.atProfileScope(scope, read);
  }

  private static List<String> contents(List<Memory> memories) {
    return memories.stream().map(Memory::content).toList();
  }

  @Test
  void fullTextSearchReadsTheSubtreeOnlyWhenScoped() {
    put(parent, "the parent uses gradle", null);
    put(child, "the child uses gradle too", null);
    put(sibling, "the sibling uses gradle as well", null);

    assertThat(contents(inScope(() -> store.searchMemoriesFts(parent.id(), "gradle", 10))))
      .containsExactlyInAnyOrder("the parent uses gradle", "the child uses gradle too");
    assertThat(contents(store.searchMemoriesFts(parent.id(), "gradle", 10)))
      .containsExactly("the parent uses gradle");
    // A query for another profile is never widened by the bound scope.
    assertThat(contents(inScope(() -> store.searchMemoriesFts(child.id(), "gradle", 10))))
      .containsExactly("the child uses gradle too");
  }

  @Test
  void exactKeyKeepsCompetingFactsFromEachProfile() {
    put(parent, "build tool is gradle", "build.tool");
    put(child, "build tool is maven", "build.tool");

    assertThat(contents(inScope(() -> store.exactKeyLookup(parent.id(), List.of("build.tool"), 10))))
      .containsExactlyInAnyOrder("build tool is gradle", "build tool is maven");
    assertThat(contents(store.exactKeyLookup(child.id(), List.of("build.tool"), 10)))
      .containsExactly("build tool is maven");
  }

  @Test
  void messageSearchMatchesASessionOnlyInsideItsOwnProfile() {
    put(parent, "parent memory from the shared session", null);
    put(child, "child memory from the shared session", null);
    store.insertMessages(child.id(), "shared-session",
      List.of(new Message(null, "shared-session", "user", "secretkeyword", Instant.now())));

    // Only the child said the keyword, so only the child's memory of that session matches, even
    // though the parent has a session with the same id.
    assertThat(contents(inScope(() -> store.searchMemoriesByMessageFts(parent.id(), "secretkeyword", 10))))
      .containsExactly("child memory from the shared session");
    assertThat(store.searchMemoriesByMessageFts(parent.id(), "secretkeyword", 10)).isEmpty();
  }

  @Test
  void graphHopsReachTheChildsEntities() {
    Memory owns = put(child, "Alice owns the billing service", null);
    store.attachGraph(child.id(), owns.id(), new GraphFragment(
      List.of(Entity.of("person", "alice", "{}"), Entity.of("service", "billing", "{}")),
      List.of(new GraphFragment.EdgeTriple("alice", "person", "owns", "billing", "service"))));

    List<Entity> seeds = inScope(() -> store.findEntitiesByName(parent.id(), List.of("alice"), 10));
    assertThat(seeds).hasSize(1);
    List<String> reached = inScope(() -> store.neighborhood(parent.id(), List.of(seeds.getFirst().id()), 2, 10));
    assertThat(reached).hasSize(2);
    assertThat(contents(inScope(() -> store.findMemoriesByEntities(parent.id(), reached, 10))))
      .containsExactly("Alice owns the billing service");
    assertThat(inScope(() -> store.entitiesForMemories(parent.id(), List.of(owns.id()), 10))).hasSize(2);
    assertThat(store.findEntitiesByName(parent.id(), List.of("alice"), 10)).isEmpty();
  }

  @Test
  void embeddingsAndTokenCountsCoverTheChildsMemories() {
    Memory inChild = put(child, "child memory with a vector", null);
    // Written straight to the column: the vector extension is not needed to read embeddings back.
    jdbc.sql("UPDATE memories SET embedding = ?, source_tokens = 42 WHERE id = ?")
      .params(dev.alvo.pieria.tools.EmbeddingCodec.encode(new float[]{1f, 0f, 0f, 0f}), inChild.id()).update();

    assertThat(inScope(() -> store.embeddingsFor(parent.id(), List.of(inChild.id())))).containsKey(inChild.id());
    assertThat(store.embeddingsFor(parent.id(), List.of(inChild.id()))).isEmpty();
    assertThat(inScope(() -> store.sumActiveSourceTokens(parent.id(), List.of(inChild.id())))).isEqualTo(42L);
    assertThat(store.sumActiveSourceTokens(parent.id(), List.of(inChild.id()))).isZero();
  }

  @Test
  void codeIndexPresenceCountsAnyProfileInTheScope() {
    assertThat(inScope(() -> codeStore.isCodeIndexPresent(parent.id()))).isFalse();
    // No code in either profile yet: presence stays false whichever way it is asked.
    assertThat(codeStore.isCodeIndexPresent(child.id())).isFalse();
  }

  @Test
  void aScopeWithoutChildrenRendersThePlainPredicate() {
    assertThat(ProfileScopeSql.within(ProfileScope.single(sibling.id()),
      () -> ProfileScopeSql.matches("m.profile_id", sibling.id()))).isEqualTo("m.profile_id = ?");
    assertThat(ProfileScopeSql.matches("m.profile_id", parent.id())).isEqualTo("m.profile_id = ?");
    assertThat(ProfileScopeSql.within(scope, () -> ProfileScopeSql.matches("m.profile_id", parent.id())))
      .isEqualTo("(m.profile_id = ? OR m.profile_id IN ('" + child.id() + "'))");
  }
}
