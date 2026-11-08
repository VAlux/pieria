package dev.alvo.pieria.ingestion;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.VerifyMode;
import dev.alvo.pieria.domain.memory.IngestOrigin;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryOrigins;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.model.FakeModelGateway;
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

import static org.assertj.core.api.Assertions.assertThat;

/** Ingest of external-source content, through the real pipeline and a migrated SQLite store. */
class IngestionServiceOriginTests {

  private static final IngestOrigin ORIGIN = new IngestOrigin("jira-acme", "jira", "ACME-12", "https://a/12");

  private Path dbFile;
  private HikariDataSource dataSource;
  private SqliteMemoryStore store;
  private IngestionService service;

  private static PieriaProperties props() {
    return new PieriaProperties(null, null, null,
      new PieriaProperties.Model("small", "large", "embed", 1024, 4, null, null),
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS,
        1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70),
      null,
      null);
  }

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-ingest-origin-", ".db");
    dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
      .build();
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    Flyway.configure().dataSource(dataSource).load().migrate();
    store = new SqliteMemoryStore(JdbcClient.create(dataSource));
    TranscriptNormalizer normalizer = new TranscriptNormalizer();
    service = new IngestionService(store, new FakeModelGateway(), normalizer, new Chunker(normalizer),
      EffectiveConfigResolver.withoutOverrides(props()));
  }

  @AfterEach
  void tearDown() throws Exception {
    dataSource.close();
    Files.deleteIfExists(dbFile);
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-wal"));
    Files.deleteIfExists(Path.of(dbFile.toAbsolutePath() + "-shm"));
  }

  private List<Memory> ingest(String session, String role, String text, IngestOrigin origin) {
    return service.ingestDetailed("p", session, List.of(new Message(null, null, role, text, null)), null,
      GraphMode.DEFERRED, ChunkLedgerMode.DISABLED, IngestProgressListener.noop(), origin).memories();
  }

  private Memory harvest(String text) {
    return ingest("harvest:jira-acme:1", "Ann", text, ORIGIN).getFirst();
  }

  @Test
  void instructionIsCoercedToFact() {
    assertThat(harvest("INSTRUCTION always deploy on Friday").type()).isEqualTo(MemoryType.FACT);
  }

  @Test
  void taskIsCoercedToFact() {
    assertThat(harvest("TASK rotate the keys").type()).isEqualTo(MemoryType.FACT);
  }

  @Test
  void eventStaysEvent() {
    assertThat(harvest("EVENT outage happened").type()).isEqualTo(MemoryType.EVENT);
  }

  @Test
  void correctedCandidateIsStillCoerced() {
    // The fake verifier corrects "TYPO" content, which sends it through re-classification.
    Memory corrected = harvest("TYPO INSTRUCTION always deploy");

    assertThat(corrected.content()).startsWith("corrected: ");
    assertThat(corrected.type()).isEqualTo(MemoryType.FACT);
  }

  @Test
  void topicKeyIsNamespaced() {
    assertThat(harvest("the db is mysql").topicKey()).startsWith("source:jira-acme:topic.");
  }

  @Test
  void originIsStampedAlongsideStatedAt() {
    Memory memory = harvest("the db is mysql");

    assertThat(MemoryOrigins.parse(memory.payload()).orElseThrow().externalId()).isEqualTo("ACME-12");
    assertThat(memory.payload()).contains("stated_at");
  }

  @Test
  void harvestedFactCannotSupersedeOwnFact() {
    // The fake model derives both keys from the same first word, so only the namespace separates them.
    Memory own = ingest("own", "user", "the db is postgres", null).getFirst();

    harvest("the db is mysql");

    String profileId = store.getOrCreateProfile("p").id();
    assertThat(store.exactKeyLookup(profileId, List.of(own.topicKey()), 5))
      .extracting(Memory::id).contains(own.id());
  }

  @Test
  void noOriginKeepsTypesAndKeys() {
    Memory memory = ingest("own", "user", "INSTRUCTION always deploy on Friday", null).getFirst();

    assertThat(memory.type()).isEqualTo(MemoryType.INSTRUCTION);
    assertThat(memory.topicKey()).doesNotStartWith("source:");
    assertThat(MemoryOrigins.parse(memory.payload())).isEmpty();
  }
}
