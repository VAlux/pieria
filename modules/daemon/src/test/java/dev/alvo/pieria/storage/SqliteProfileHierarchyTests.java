package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.domain.profile.ProfileCount;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The profile tree as the SQLite store records it: parent links, subtree resolution, owners of
 * memories, and children moving up when their parent is deleted.
 */
class SqliteProfileHierarchyTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore store;

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-hierarchy-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    Flyway.configure().dataSource(dataSource).load().migrate();
    store = new SqliteMemoryStore(JdbcClient.create(dataSource));
  }

  @AfterEach
  void tearDown() throws Exception {
    dataSource.close();
    Files.deleteIfExists(dbFile);
  }

  @Test
  void aProfileWithoutChildrenIsItsOwnSubtree() {
    Profile solo = store.getOrCreateProfile("solo");

    assertThat(store.profileSubtree(solo.id())).containsExactly(solo.id());
    assertThat(store.findProfile("solo").orElseThrow().parentId()).isNull();
  }

  @Test
  void subtreeListsTheProfileFirstThenEveryDescendantByDepthAndName() {
    Profile root = store.createProfile("root", null);
    Profile b = store.createProfile("b", root.id());
    Profile a = store.createProfile("a", root.id());
    Profile deep = store.createProfile("deep", b.id());
    store.createProfile("elsewhere", null);

    assertThat(store.profileSubtree(root.id())).containsExactly(root.id(), a.id(), b.id(), deep.id());
    assertThat(store.profileSubtree(b.id())).containsExactly(b.id(), deep.id());
    assertThat(store.findProfile("deep").orElseThrow().parentId()).isEqualTo(b.id());
  }

  @Test
  void listingNamesEachProfilesParent() {
    Profile root = store.createProfile("root", null);
    store.createProfile("child", root.id());

    Map<String, String> parents = new java.util.HashMap<>();
    for (ProfileCount count : store.listProfiles()) {
      parents.put(count.profile().name(), count.parentName());
    }
    assertThat(parents).containsEntry("child", "root").containsEntry("root", null);
  }

  @Test
  void movingToTopLevelClearsTheParent() {
    Profile root = store.createProfile("root", null);
    Profile child = store.createProfile("child", root.id());

    store.setProfileParent(child.id(), null);

    assertThat(store.profileSubtree(root.id())).containsExactly(root.id());
    assertThat(store.findProfile("child").orElseThrow().parentId()).isNull();
  }

  @Test
  void deletingAParentMovesItsChildrenUpAndKeepsTheirMemories() {
    Profile top = store.createProfile("top", null);
    Profile middle = store.createProfile("middle", top.id());
    Profile leaf = store.createProfile("leaf", middle.id());
    store.store(leaf.id(), Memory.of(MemoryType.FACT, "leaf keeps this", "s1", null, null));

    store.deleteProfile(middle.id());

    assertThat(store.findProfile("middle")).isEmpty();
    assertThat(store.findProfile("leaf").orElseThrow().parentId()).isEqualTo(top.id());
    assertThat(store.profileSubtree(top.id())).containsExactly(top.id(), leaf.id());
    assertThat(store.listMemories(leaf.id(), null, null, false)).hasSize(1);
  }

  @Test
  void deletingATopLevelParentLeavesItsChildrenAtTopLevel() {
    Profile top = store.createProfile("top", null);
    Profile child = store.createProfile("child", top.id());

    store.deleteProfile(top.id());

    assertThat(store.findProfile("child").orElseThrow().parentId()).isNull();
    assertThat(store.profileSubtree(child.id())).containsExactly(child.id());
  }

  @Test
  void ownerNamesMapEachMemoryToItsProfile() {
    Profile a = store.getOrCreateProfile("a");
    Profile b = store.getOrCreateProfile("b");
    String inA = store.store(a.id(), Memory.of(MemoryType.FACT, "fact in a", "s", null, null)).stored().id();
    String inB = store.store(b.id(), Memory.of(MemoryType.FACT, "fact in b", "s", null, null)).stored().id();

    assertThat(store.memoryOwnerNames(List.of(inA, inB, "missing")))
      .containsExactlyInAnyOrderEntriesOf(Map.of(inA, "a", inB, "b"));
    assertThat(store.memoryOwnerNames(List.of())).isEmpty();
  }
}
