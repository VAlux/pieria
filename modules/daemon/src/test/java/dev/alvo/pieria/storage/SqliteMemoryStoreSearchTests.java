package dev.alvo.pieria.storage;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemorySearchPage;
import dev.alvo.pieria.domain.memory.MemorySearchQuery;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.memory.SessionActivity;
import dev.alvo.pieria.domain.memory.TopicKeyEntry;
import dev.alvo.pieria.domain.profile.Profile;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The console's memory list, session list and topic-key lookup against a real migrated SQLite file. */
class SqliteMemoryStoreSearchTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore store;
  private Profile profile;
  private final Instant base = Instant.now().minus(Duration.ofHours(2));

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-search-test-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    Flyway.configure().dataSource(dataSource).load().migrate();
    store = new SqliteMemoryStore(JdbcClient.create(dataSource));
    profile = store.getOrCreateProfile("search");
  }

  @AfterEach
  void tearDown() throws Exception {
    dataSource.close();
    Files.deleteIfExists(dbFile);
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
  }

  private Memory insert(int minute, MemoryType type, String content, String session, String key, String payload) {
    return store.insertMemory(profile.id(), new Memory(null, session, type, content, key, null, false,
      payload == null ? "{}" : payload, null, base.plus(Duration.ofMinutes(minute))));
  }

  private static MemorySearchQuery query(String text, MemoryType type, String session, boolean newest, int limit,
                                         MemorySearchQuery.Cursor cursor, boolean grouped) {
    return new MemorySearchQuery(text, type, session, newest, limit, cursor, false, grouped);
  }

  private static List<String> contents(MemorySearchPage page) {
    return page.entries().stream().map(e -> e.memory() == null ? "group:" + e.group().sessionId() : e.memory().content()).toList();
  }

  @Test
  void searchesContentTopicKeyAndSession() {
    insert(1, MemoryType.FACT, "The daemon serves the console", "alpha-session", "web.console.serving", null);
    insert(2, MemoryType.FACT, "Embeddings use a fixed dimension", "beta-session", "storage.dimension", null);
    insert(3, MemoryType.TASK, "Port the graph view", "beta-session", null, null);

    assertEquals(List.of("The daemon serves the console"), contents(store.searchMemories(profile.id(), query("serv", null, null, true, 10, null, false))));
    assertEquals(List.of("Embeddings use a fixed dimension"), contents(store.searchMemories(profile.id(), query("dimension", null, null, true, 10, null, false))));
    assertEquals(List.of("The daemon serves the console"), contents(store.searchMemories(profile.id(), query("console.serv", null, null, true, 10, null, false))));
    assertEquals(List.of("Port the graph view", "Embeddings use a fixed dimension"),
      contents(store.searchMemories(profile.id(), query("beta-sess", null, null, true, 10, null, false))));
  }

  @Test
  void pagesWithStableCursorsInBothDirections() {
    for (int i = 0; i < 5; i++) insert(i, MemoryType.FACT, "fact " + i, "s", null, null);

    MemorySearchPage first = store.searchMemories(profile.id(), query(null, null, null, true, 2, null, false));
    assertEquals(List.of("fact 4", "fact 3"), contents(first));
    assertNotNull(first.next());
    MemorySearchPage second = store.searchMemories(profile.id(), query(null, null, null, true, 2, first.next(), false));
    assertEquals(List.of("fact 2", "fact 1"), contents(second));
    MemorySearchPage third = store.searchMemories(profile.id(), query(null, null, null, true, 2, second.next(), false));
    assertEquals(List.of("fact 0"), contents(third));
    assertNull(third.next());

    MemorySearchPage oldest = store.searchMemories(profile.id(), query(null, null, null, false, 3, null, false));
    assertEquals(List.of("fact 0", "fact 1", "fact 2"), contents(oldest));
  }

  @Test
  void typeCountsIgnoreTheTypeFilterButTotalRespectsIt() {
    insert(1, MemoryType.FACT, "a fact about search", "s1", null, null);
    insert(2, MemoryType.FACT, "another fact", "s1", null, null);
    insert(3, MemoryType.TASK, "a task about search", "s2", null, null);

    MemorySearchPage page = store.searchMemories(profile.id(), query(null, MemoryType.TASK, null, true, 10, null, false));
    assertEquals(2L, page.typeCounts().get("fact"));
    assertEquals(1L, page.typeCounts().get("task"));
    assertEquals(1L, page.total());

    MemorySearchPage searched = store.searchMemories(profile.id(), query("search", null, null, true, 10, null, false));
    assertEquals(1L, searched.typeCounts().get("fact"));
    assertEquals(2L, searched.total());

    MemorySearchPage bySession = store.searchMemories(profile.id(), query(null, null, "s1", true, 10, null, false));
    assertEquals(2L, bySession.total());
  }

  @Test
  void supersededRowsOnlyWhenAsked() {
    store.store(profile.id(), Memory.of(MemoryType.FACT, "old value", "s", "k", null));
    store.store(profile.id(), Memory.of(MemoryType.FACT, "new value", "s", "k", null));

    assertEquals(List.of("new value"), contents(store.searchMemories(profile.id(), query(null, null, null, true, 10, null, false))));
    MemorySearchPage all = store.searchMemories(profile.id(), new MemorySearchQuery(null, null, null, true, 10, null, true, false));
    assertTrue(contents(all).containsAll(List.of("old value", "new value")));
  }

  @Test
  void groupsTraceOutcomesPerSession() {
    insert(1, MemoryType.EVENT, "ran ls", "run-1", "trace:outcome:ls", "{\"tool\":\"Bash\"}");
    insert(2, MemoryType.EVENT, "read a file", "run-1", "trace:outcome:read", "{\"tool\":\"Read\"}");
    insert(3, MemoryType.FACT, "a plain fact", "run-1", null, null);
    insert(4, MemoryType.EVENT, "ran make", "run-2", "trace:outcome:make", "{\"tool\":\"Bash\"}");
    insert(5, MemoryType.EVENT, "a conversational event", "run-2", null, null);

    MemorySearchPage page = store.searchMemories(profile.id(), query(null, null, null, true, 10, null, true));
    assertEquals(List.of("a conversational event", "group:run-2", "a plain fact", "group:run-1"), contents(page));
    MemorySearchPage.ToolCallGroup runOne = page.entries().get(3).group();
    assertEquals(2, runOne.count());
    assertEquals(List.of("Bash", "Read"), runOne.tools().stream().sorted().toList());
    assertTrue(runOne.firstAt().isBefore(runOne.lastAt()));
    assertEquals(4L, page.typeCounts().get("event"));

    MemorySearchPage firstTwo = store.searchMemories(profile.id(), query(null, null, null, true, 2, null, true));
    MemorySearchPage rest = store.searchMemories(profile.id(), query(null, null, null, true, 2, firstTwo.next(), true));
    assertEquals(List.of("a plain fact", "group:run-1"), contents(rest));
  }

  @Test
  void findsOneMemoryOfThisProfileOnly() {
    Memory mine = insert(1, MemoryType.FACT, "mine", "s", null, null);
    Profile other = store.getOrCreateProfile("other");
    Memory theirs = store.insertMemory(other.id(), Memory.of(MemoryType.FACT, "theirs", "s", null, null));

    assertEquals("mine", store.findMemory(profile.id(), mine.id()).orElseThrow().content());
    assertTrue(store.findMemory(profile.id(), theirs.id()).isEmpty());
    store.forgetMemory(profile.id(), mine.id());
    assertTrue(store.findMemory(profile.id(), mine.id()).isEmpty());
  }

  @Test
  void listsSessionsFromMemoriesAndMessages() {
    insert(1, MemoryType.FACT, "from memories", "session-a", null, null);
    insert(30, MemoryType.FACT, "later", "session-a", null, null);
    store.insertMessages(profile.id(), "session-b", List.of(
      new Message(null, "session-b", "user", "hello", base.plus(Duration.ofMinutes(60)))));

    List<SessionActivity> sessions = store.listSessions(profile.id(), null, 10);
    assertEquals(List.of("session-b", "session-a"), sessions.stream().map(SessionActivity::sessionId).toList());
    assertEquals(0, sessions.get(0).memoryCount());
    assertEquals(1, sessions.get(0).messageCount());
    assertEquals(2, sessions.get(1).memoryCount());
    assertEquals(base.plus(Duration.ofMinutes(1)), sessions.get(1).firstSeenAt());

    assertEquals(List.of("session-a"), store.listSessions(profile.id(), "session-a", 10).stream().map(SessionActivity::sessionId).toList());
  }

  @Test
  void topicKeysReturnTheVisibleMemoryPerTypeAndKey() {
    store.store(profile.id(), Memory.of(MemoryType.FACT, "old serving", "s", "web.console.serving", null));
    store.store(profile.id(), Memory.of(MemoryType.FACT, "new serving", "s", "web.console.serving", null));
    store.store(profile.id(), Memory.of(MemoryType.INSTRUCTION, "serve politely", "s", "web.console.serving", null));
    store.store(profile.id(), Memory.of(MemoryType.FACT, "packaging", "s", "console.packaging", null));
    insert(1, MemoryType.EVENT, "ran ls", "s", "trace:outcome:console", null);

    List<TopicKeyEntry> facts = store.searchTopicKeys(profile.id(), "console", MemoryType.FACT, false, 10);
    assertEquals(List.of("console.packaging", "web.console.serving"), facts.stream().map(TopicKeyEntry::topicKey).toList());
    assertEquals("new serving", facts.get(1).memory().content());

    List<String> any = new ArrayList<>(store.searchTopicKeys(profile.id(), "serving", null, false, 10).stream()
      .map(e -> e.memory().type().wire()).sorted().toList());
    assertEquals(List.of("fact", "instruction"), any);

    assertTrue(store.searchTopicKeys(profile.id(), "trace", null, false, 10).isEmpty());
    assertEquals(1, store.searchTopicKeys(profile.id(), "trace", null, true, 10).size());
  }
}
