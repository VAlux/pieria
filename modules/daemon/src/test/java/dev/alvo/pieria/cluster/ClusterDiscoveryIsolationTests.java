package dev.alvo.pieria.cluster;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.domain.memory.IngestOrigin;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryOrigins;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Discovery's neighbour search must neither admit harvested memories nor be crowded out by them. */
class ClusterDiscoveryIsolationTests {

  private static final String STAMPED = MemoryOrigins.withOrigin("{}", new IngestOrigin("jira-acme", "jira", "1", null));
  private static final String SENTENCE = "the storage writer serializes commits with a queue ";

  @TempDir
  Path temp;

  private HikariDataSource dataSource;
  private MemoryStore store;
  private String profile;

  @BeforeEach
  void setUp() {
    dataSource = new HikariDataSource();
    dataSource.setJdbcUrl("jdbc:sqlite:" + temp.resolve("discovery.db"));
    Flyway.configure().dataSource(dataSource).load().migrate();
    store = new SqliteMemoryStore(JdbcClient.create(dataSource));
    profile = store.getOrCreateProfile("discovery").id();
  }

  @AfterEach
  void tearDown() {
    dataSource.close();
  }

  private Memory insert(String content, String payload) {
    return store.insertMemory(profile, Memory.of(MemoryType.EVENT, content, "fixture", null, payload));
  }

  @Test
  void harvestedNeighboursAreNotAdmittedAndCannotCrowdOutOwnMemories() {
    for (int i = 0; i < 20; i++) {
      insert(SENTENCE + i, STAMPED);
    }
    Memory seed = insert("own " + SENTENCE + "and a longer tail of own words here", "{}");
    Memory second = insert("own " + SENTENCE + "and another longer tail of own words", "{}");
    Memory third = insert("own " + SENTENCE + "and yet another longer tail of own words", "{}");

    var topic = new ClusterDiscoveryService(store).discover(profile, seed.id());

    assertThat(topic).isNotNull();
    assertThat(topic.members()).extracting(ClusterRepository.Member::id)
      .containsExactlyInAnyOrder(seed.id(), second.id(), third.id());
  }
}
