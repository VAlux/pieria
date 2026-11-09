package dev.alvo.pieria.harvest;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.cluster.ClusterRepository;
import dev.alvo.pieria.cluster.ClusterRepository.Member;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.IngestOrigin;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryOrigins;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cluster and wiki generation can't carry the untrusted label yet, so harvested text stays out. */
class HarvestedTextIsolationTests {

  private static final String STAMPED = MemoryOrigins.withOrigin("{}", new IngestOrigin("jira-acme", "jira", "1", null));

  @TempDir
  Path temp;

  private HikariDataSource dataSource;
  private JdbcClient jdbc;
  private MemoryStore store;
  private String profile;

  @BeforeEach
  void setUp() {
    dataSource = new HikariDataSource();
    dataSource.setJdbcUrl("jdbc:sqlite:" + temp.resolve("isolation.db") + "?busy_timeout=10000&foreign_keys=on&transaction_mode=IMMEDIATE");
    dataSource.setMaximumPoolSize(4);
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = JdbcClient.create(dataSource);
    ProxyFactory proxy = new ProxyFactory(new SqliteMemoryStore(jdbc));
    proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
    store = (MemoryStore) proxy.getProxy();
    profile = store.getOrCreateProfile("isolation").id();
  }

  @AfterEach
  void tearDown() {
    dataSource.close();
  }

  private Memory insert(String content, String key, String payload) {
    return store.insertMemory(profile, Memory.of(MemoryType.EVENT, content, "fixture", key, payload));
  }

  private static GraphFragment graph(String subject) {
    return new GraphFragment(List.of(Entity.of("tool", subject, null), Entity.of("concept", "sessions", null)),
      List.of(new GraphFragment.EdgeTriple(subject, "tool", "powers", "sessions", "concept")));
  }

  private Memory insertWithGraph(String content, String subject, String payload) {
    return store.store(profile, Memory.of(MemoryType.EVENT, content, "fixture", null, payload), graph(subject)).stored();
  }

  private TemporalSnapshot now() {
    return store.temporalSnapshot(null, null);
  }

  private List<ClusterRepository.Topic> topics() {
    return store.readClusters(repo -> repo.topics(profile, null, 10, now()));
  }

  private List<ClusterRepository.Topic> entityTopics() {
    return store.readClusters(repo -> repo.entityTopics(profile, null, 10, now()));
  }

  @Test
  void clusterTopicsIgnoreHarvestedMemories() {
    for (int i = 0; i < 3; i++) {
      insert("harvested " + i, "source:jira-acme:key", STAMPED);
    }

    assertThat(topics()).isEmpty();
  }

  @Test
  void clusterEntityTopicsIgnoreHarvestedMemories() {
    for (int i = 0; i < 3; i++) {
      insertWithGraph("harvested " + i, "kafka", STAMPED);
    }

    assertThat(entityTopics()).isEmpty();
  }

  @Test
  void entityTopicMembersExcludeHarvestedMemories() {
    for (int i = 0; i < 3; i++) {
      insertWithGraph("own " + i, "redis", "{}");
    }
    Memory harvested = insertWithGraph("harvested", "redis", STAMPED);

    assertThat(entityTopics()).isNotEmpty().allSatisfy(topic ->
      assertThat(topic.members()).extracting(Member::id).doesNotContain(harvested.id()));
  }

  @Test
  void clusterSeedsIgnoreHarvestedMemories() {
    Memory own = insert("own", null, "{}");
    insert("harvested", null, STAMPED);

    assertThat(store.<List<String>>readClusters(repo -> repo.discoverySeeds(profile, null, 10, now(), -1, -1)))
      .containsExactly(own.id());
  }

  @Test
  void aHarvestedMemoryCannotBeAddedToACluster() {
    Member own = new Member("memory", insert("own", null, "{}").id());
    Member harvested = new Member("memory", insert("harvested", null, STAMPED).id());

    assertThatThrownBy(() -> store.writeClusters(repo -> repo.create(profile, "T", "scope", List.of(own, harvested), "test")))
      .isInstanceOf(IllegalArgumentException.class);
  }

  private <T> T wiki(java.util.function.Function<dev.alvo.pieria.wiki.WikiRepository, T> work) {
    return store.readWiki(work);
  }

  @Test
  void wikiSourcesIgnoreHarvestedMemories() {
    insert("own", null, "{}");
    insert("harvested", null, STAMPED);

    var sources = wiki(repo -> repo.sources(profile, now(), "", 10));

    assertThat(sources).hasSize(1);
  }

  @Test
  void wikiSourceLookupIgnoresHarvestedMemories() {
    Memory harvested = insert("harvested", null, STAMPED);

    var source = wiki(repo -> repo.source(profile, harvested.id(), now()));

    assertThat(source).isEmpty();
  }

  @Test
  void ownMemoriesStillReachClustersAndWikis() {
    Member first = new Member("memory", insert("own one", "k.shared", "{}").id());
    Member second = new Member("memory", insert("own two", "k.shared", "{}").id());
    Memory third = insert("own three", "k.shared", "{}");

    assertThat(topics()).hasSize(1);
    ClusterRepository.Cluster created = store.writeClusters(repo -> repo.create(profile, "T", "scope", List.of(first, second), "test"));
    assertThat(created).isNotNull();
    var source = wiki(repo -> repo.source(profile, third.id(), now()));
    assertThat(source).isPresent();
  }
}
