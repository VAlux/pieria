package dev.alvo.pieria.onboarding;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.api.response.OnboardResult;
import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.VerifyMode;
import dev.alvo.pieria.ingestion.Chunker;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.ingestion.TranscriptNormalizer;
import dev.alvo.pieria.ingestion.model.Chunk;
import dev.alvo.pieria.ingestion.model.UnifiedCandidate;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ContentIngestor}'s pure text-splitting (section detection, provenance
 * prefixing, hard-splitting, batching) plus the incremental-ingest ledger behavior against a real
 * SQLite store and the deterministic {@link FakeModelGateway}.
 */
class ContentIngestorTests {

  @Test
  void splitsOnTopLevelHeadings() {
    List<String> sections = ContentIngestor.splitIntoSections(
      "# Title\nintro\n\n## One\nbody one\n\n## Two\nbody two\n");

    assertThat(sections).hasSize(3);
    assertThat(sections.get(0)).startsWith("# Title");
    assertThat(sections.get(1)).startsWith("## One");
    assertThat(sections.get(2)).startsWith("## Two");
  }

  @Test
  void keepsPreambleBeforeFirstHeading() {
    List<String> sections = ContentIngestor.splitIntoSections("preamble text\n\n# Heading\nbody");

    assertThat(sections.get(0)).isEqualTo("preamble text\n\n");
    assertThat(sections.get(1)).startsWith("# Heading");
  }

  @Test
  void wholeTextIsOneSectionWhenNoHeadings() {
    assertThat(ContentIngestor.splitIntoSections("just a paragraph\nno headings"))
      .containsExactly("just a paragraph\nno headings");
  }

  @Test
  void messagesCarryProvenancePrefix() {
    List<String> contents = ContentIngestor.toMessageContents(
      new ContentDocument("Project documentation — docs/SPEC.md", "# Title\nbody"));

    assertThat(contents).allSatisfy(c ->
      assertThat(c).startsWith("Project documentation — docs/SPEC.md:"));
  }

  @Test
  void whitespaceOnlyDocumentYieldsNoMessages() {
    assertThat(ContentIngestor.toMessageContents(new ContentDocument("Web page — https://x", "   \n\n  \n")))
      .isEmpty();
  }

  @Test
  void hardSplitKeepsPiecesUnderLimit() {
    String huge = "x".repeat(20_000);
    List<String> pieces = ContentIngestor.hardSplit(huge, 8_000);

    assertThat(pieces).hasSizeGreaterThan(1);
    assertThat(pieces).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(8_000));
  }

  @Test
  void hardSplitPrefersParagraphBoundaries() {
    String section = "a".repeat(5_000) + "\n\n" + "b".repeat(5_000);
    List<String> pieces = ContentIngestor.hardSplit(section, 8_000);

    assertThat(pieces).hasSize(2);
    assertThat(pieces.get(0)).startsWith("a").doesNotContain("b");
    assertThat(pieces.get(1)).startsWith("b");
  }

  @Test
  void batchingPacksSmallDocsAndIsolatesOversizedOnes() {
    ContentDocument small1 = new ContentDocument("a", "x".repeat(10_000));
    ContentDocument small2 = new ContentDocument("b", "y".repeat(10_000));
    ContentDocument huge = new ContentDocument("c", "z".repeat(50_000));

    List<List<ContentDocument>> batches = ContentIngestor.batchByBudget(List.of(small1, small2, huge));

    assertThat(batches).hasSize(2);
    assertThat(batches.get(0)).containsExactly(small1, small2);
    assertThat(batches.get(1)).containsExactly(huge);
  }

  @Test
  void contentHashChangesWithTextAndSamples() {
    ContentDocument doc = new ContentDocument("docs/SPEC.md", "content");
    String base = ContentIngestor.contentHash(doc, null);

    assertThat(ContentIngestor.contentHash(doc, null)).isEqualTo(base);
    assertThat(ContentIngestor.contentHash(new ContentDocument("docs/SPEC.md", "changed"), null))
      .isNotEqualTo(base);
    assertThat(ContentIngestor.contentHash(doc, 3)).isNotEqualTo(base);
  }

  /**
   * Ledger behavior: unchanged documents skip the model pipeline, changed ones re-ingest, refresh
   * bypasses the check, and an interrupted run resumes past its completed batches.
   */
  @Nested
  class LedgerBehavior {

    private Path dbFile;
    private HikariDataSource dataSource;
    private SqliteMemoryStore store;

    /** Counts unified-extraction calls; can be told to fail on transcripts containing a marker. */
    private static class CountingGateway extends FakeModelGateway {
      final AtomicInteger extractCalls = new AtomicInteger();
      final AtomicInteger graphCalls = new AtomicInteger();
      volatile String failMarker;

      @Override
      public List<UnifiedCandidate> extractUnified(Chunk chunk) {
        if (failMarker != null && chunk.transcript() != null && chunk.transcript().contains(failMarker)) {
          throw new ModelUnavailableException("scripted failure");
        }
        extractCalls.incrementAndGet();
        return super.extractUnified(chunk);
      }

      @Override
      public List<GraphFragment> extractGraphAll(List<String> contents) {
        graphCalls.incrementAndGet();
        return super.extractGraphAll(contents);
      }
    }

    private CountingGateway gateway;
    private ContentIngestor ingestor;

    @BeforeEach
    void setUp() throws Exception {
      dbFile = Files.createTempFile("pieria-content-ingest-", ".db");
      dataSource = DataSourceBuilder.create()
        .type(HikariDataSource.class)
        .driverClassName("org.sqlite.JDBC")
        .url("jdbc:sqlite:" + dbFile.toAbsolutePath())
        .build();
      dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
      Flyway.configure().dataSource(dataSource).load().migrate();

      store = new SqliteMemoryStore(JdbcClient.create(dataSource));
      TranscriptNormalizer normalizer = new TranscriptNormalizer();
      gateway = new CountingGateway();
      PieriaProperties props = new PieriaProperties(null, null, null,
      new PieriaProperties.Model("small", "large", "embed", 1024, 4, null, null),
        new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS, 1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70),
        null, null);
      IngestionService ingestionService = new IngestionService(store, gateway, normalizer,
        new Chunker(normalizer), EffectiveConfigResolver.withoutOverrides(props));
      ingestor = new ContentIngestor(ingestionService, store);
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

    private OnboardResult run(List<ContentDocument> docs, boolean refresh) {
      return ingestor.ingest("proj", "markdown", docs, null, refresh, IngestProgressListener.noop());
    }

    @Test
    void onboardingDefersGraphButStoresAndEnqueuesMemories() {
      OnboardResult result = run(
        List.of(new ContentDocument("docs/a.md", "redis powers durable sessions")), false);

      assertThat(result.memoriesStored()).isPositive();
      assertThat(result.graphDeferred()).isEqualTo(result.memoriesStored());
      assertThat(gateway.graphCalls.get()).isZero();
      assertThat(store.drainOutbox(10)).hasSize(result.memoriesStored());
      String profileId = store.findProfile("proj").orElseThrow().id();
      assertThat(store.countGraphOrphans(profileId)).isEqualTo(result.graphDeferred());
    }

    @Test
    void secondIdenticalRunSkipsEveryDocumentAndAllModelCalls() {
      List<ContentDocument> docs = List.of(
        new ContentDocument("docs/a.md", "alpha content"),
        new ContentDocument("docs/b.md", "bravo content"));

      OnboardResult first = run(docs, false);
      assertThat(first.documentsSkipped()).isZero();
      assertThat(gateway.extractCalls.get()).isPositive();

      int callsAfterFirst = gateway.extractCalls.get();
      OnboardResult second = run(docs, false);

      assertThat(second.documentsSkipped()).isEqualTo(2);
      assertThat(second.memoriesStored()).isZero();
      assertThat(gateway.extractCalls.get())
        .as("an unchanged corpus must trigger no model calls")
        .isEqualTo(callsAfterFirst);
    }

    @Test
    void onlyTheChangedDocumentReingests() {
      run(List.of(
        new ContentDocument("docs/a.md", "alpha content"),
        new ContentDocument("docs/b.md", "bravo content")), false);

      OnboardResult second = run(List.of(
        new ContentDocument("docs/a.md", "alpha content"),
        new ContentDocument("docs/b.md", "bravo content CHANGED")), false);

      assertThat(second.documentsSkipped()).isEqualTo(1);
      assertThat(second.memoriesStored()).isPositive();
    }

    @Test
    void refreshBypassesTheLedger() {
      List<ContentDocument> docs = List.of(new ContentDocument("docs/a.md", "alpha content"));
      run(docs, false);
      int callsAfterFirst = gateway.extractCalls.get();

      OnboardResult refreshed = run(docs, true);

      assertThat(refreshed.documentsSkipped()).isZero();
      assertThat(gateway.extractCalls.get()).isGreaterThan(callsAfterFirst);
    }

    @Test
    void interruptedRunResumesPastCompletedBatches() {
      // Two docs big enough to land in separate batches; the second one's marker makes the model
      // fail, so the first batch completes (and is ledgered) while the run as a whole errors.
      ContentDocument good = new ContentDocument("docs/good.md", "good " + "x".repeat(30_000));
      ContentDocument bad = new ContentDocument("docs/bad.md", "POISON " + "y".repeat(30_000));

      gateway.failMarker = "POISON";
      assertThatThrownBy(() -> run(List.of(good, bad), false))
        .isInstanceOf(ModelUnavailableException.class);

      String profileId = store.getOrCreateProfile("proj").id();
      assertThat(store.ingestLedger(profileId, "markdown"))
        .as("the completed batch must be ledgered, the failed one must not")
        .containsKey("docs/good.md")
        .doesNotContainKey("docs/bad.md");

      gateway.failMarker = null;
      OnboardResult retry = run(List.of(good, bad), false);

      assertThat(retry.documentsSkipped()).as("the retry must skip the already-completed batch").isEqualTo(1);
      assertThat(retry.memoriesStored()).isPositive();
    }

    @Test
    void ledgerRoundTripsThroughTheStore() {
      String profileId = store.getOrCreateProfile("proj").id();
      assertThat(store.ingestLedger(profileId, "markdown")).isEmpty();

      store.recordIngestLedger(profileId, "markdown", java.util.Map.of("docs/a.md", "hash1"));
      assertThat(store.ingestLedger(profileId, "markdown")).containsEntry("docs/a.md", "hash1");

      store.recordIngestLedger(profileId, "markdown", java.util.Map.of("docs/a.md", "hash2"));
      assertThat(store.ingestLedger(profileId, "markdown")).containsEntry("docs/a.md", "hash2");

      assertThat(store.ingestLedger(profileId, "web")).as("scopes are independent").isEmpty();
    }
  }
}
