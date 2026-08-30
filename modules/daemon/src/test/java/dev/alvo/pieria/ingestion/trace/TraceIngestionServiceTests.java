package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.ingestion.model.OutboxEntry;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.storage.NoOpCodeIndexStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIngestionServiceTests {

  private static final Instant T1 = Instant.parse("2026-08-29T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-08-29T11:00:00Z");

  @TempDir
  Path tempDir;

  private MemoryStore store;
  private TraceIngestionService service;

  /** Returns no recipes, so these tests exercise the deterministic half in isolation. */
  private static final class SilentGateway implements ModelGateway {
    @Override
    public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
      return "";
    }

    @Override
    public float[] embed(String text) {
      return new float[0];
    }
  }

  @BeforeEach
  void setUp() {
    // Construction shared with Task 15's test class; see TraceTestSupport.
    this.store = TraceTestSupport.newSqliteStore(tempDir.resolve("trace.db"));
    this.service = new TraceIngestionService(store, new NoOpCodeIndexStore(), new SilentGateway(),
      TraceProperties.defaults(), TraceTestSupport.defaultPieriaProperties());
  }

  private static TraceEventDto trace(String args, TraceStatus status, Integer exit, String error,
                                     Instant at) {
    return new TraceEventDto("Bash", args, "", status, exit, error, null, at);
  }

  @Test
  void aFailingCommandBecomesAKeyedEventMemory() {
    List<Memory> stored = service.ingest("p", "s1",
      List.of(trace("./gradlew test", TraceStatus.FAILURE, 1, "boom", T1)));

    assertThat(stored).hasSize(1);
    assertThat(stored.getFirst().type()).isEqualTo(MemoryType.EVENT);
    assertThat(stored.getFirst().topicKey()).isEqualTo("trace:outcome:gradlew-test");
  }

  // The whole point of D5: run n+1 demotes run n rather than accumulating.
  @Test
  void aLaterOutcomeSupersedesTheEarlierOne() {
    service.ingest("p", "s1", List.of(trace("./gradlew test", TraceStatus.FAILURE, 1, "boom", T1)));
    service.ingest("p", "s2", List.of(trace("./gradlew test", TraceStatus.SUCCESS, 0, null, T2)));

    String profileId = store.getOrCreateProfile("p").id();
    List<Memory> active =
      store.findActiveByTopicKey(profileId, MemoryType.EVENT, "trace:outcome:gradlew-test");

    assertThat(active).hasSize(1);
    assertThat(active.getFirst().content()).contains("succeeded");
  }

  // D5's other half: a spool drained late must not let a stale outcome overwrite a current one.
  // The two outcomes differ in status/error on purpose — an identical late arrival would be dropped
  // by TraceRelevanceFilter's skipUnchangedOutcomes rule before it ever reached the store, which
  // would make this test pass without ever exercising the stale-on-arrival path it targets.
  @Test
  void aLateOlderOutcomeNeverSupersedesTheAlreadyActiveNewerOne() {
    // Newer outcome first.
    service.ingest("p", "s1", List.of(trace("./gradlew test", TraceStatus.SUCCESS, 0, null, T2)));
    // Then an older, genuinely different outcome for the same command arrives late.
    List<Memory> late = service.ingest("p", "s2",
      List.of(trace("./gradlew test", TraceStatus.FAILURE, 1, "boom", T1)));

    String profileId = store.getOrCreateProfile("p").id();

    // The active row is still the newer outcome, untouched.
    List<Memory> active =
      store.findActiveByTopicKey(profileId, MemoryType.EVENT, "trace:outcome:gradlew-test");
    assertThat(active).hasSize(1);
    assertThat(active.getFirst().content()).contains("succeeded");

    // The late row was stored as inert history: present, and marked superseded on arrival.
    assertThat(late).hasSize(1);
    Memory lateOutcome = late.getFirst();
    assertThat(lateOutcome.content()).contains("failed");
    assertThat(lateOutcome.superseded()).isTrue();
    assertThat(store.listMemories(profileId, MemoryType.EVENT, null, true))
      .extracting(Memory::id)
      .contains(lateOutcome.id());

    // And never embedded: a stale-on-arrival row is never enqueued for vectorization at all.
    assertThat(store.drainOutbox(50))
      .extracting(OutboxEntry::memoryId)
      .doesNotContain(lateOutcome.id());
  }

  @Test
  void reIngestingTheSameTraceIsANoOp() {
    TraceEventDto same = trace("./gradlew test", TraceStatus.FAILURE, 1, "boom", T1);

    service.ingest("p", "s1", List.of(same));
    List<Memory> second = service.ingest("p", "s1", List.of(same));

    String profileId = store.getOrCreateProfile("p").id();
    assertThat(store.listMemories(profileId, MemoryType.EVENT, null)).hasSize(1);
    assertThat(second).isEmpty();
  }

  @Test
  void noisyTracesNeverReachTheStore() {
    List<Memory> stored = service.ingest("p", "s1",
      List.of(new TraceEventDto("Read", "src/Foo.java", "…", TraceStatus.SUCCESS, 0, null, null, T1)));

    assertThat(stored).isEmpty();
    String profileId = store.getOrCreateProfile("p").id();
    assertThat(store.listMemories(profileId, null, null)).isEmpty();
  }

  @Test
  void secretsNeverReachStoredContentOrEmbedText() {
    List<Memory> stored = service.ingest("p", "s1", List.of(new TraceEventDto(
      "Bash", "deploy --token=abcd1234efgh5678", "ok", TraceStatus.SUCCESS, 0, null, null, T1)));

    assertThat(stored).hasSize(1);
    assertThat(stored.getFirst().content()).doesNotContain("abcd1234efgh5678");
    assertThat(stored.getFirst().embedText()).doesNotContain("abcd1234efgh5678");
    assertThat(stored.getFirst().payload()).doesNotContain("abcd1234efgh5678");
  }

  @Test
  void disablingTheFeatureAcceptsAndDiscards() {
    TraceProperties d = TraceProperties.defaults();
    TraceProperties off = new TraceProperties(false, d.maxOutputChars(), d.spoolMaxBytes(),
      d.spoolRetentionDays(), d.stopDrainThresholdBytes(), d.stopDrainThresholdEvents(),
      d.toolDenylist(), d.skipUnchangedOutcomes(), d.recipeExtractionEnabled(),
      d.maxRecipesPerBatch(), d.maxLinkedSymbols(), d.recallBoost());

    TraceIngestionService disabled = new TraceIngestionService(store, new NoOpCodeIndexStore(),
      new SilentGateway(), off, TraceTestSupport.defaultPieriaProperties());

    assertThat(disabled.ingest("p", "s1",
      List.of(trace("./gradlew test", TraceStatus.FAILURE, 1, "boom", T1)))).isEmpty();
  }

  // The graph fragment must reach the store with the memory, and an edge is active only while
  // its source memory is: supersession must take the old command's edges out of reach.
  @Test
  void graphEdgesArePersistedAndFollowTheirMemoryThroughSupersession() {
    service.ingest("p", "s1", List.of(trace("./gradlew test", TraceStatus.FAILURE, 1,
      "GroundingFilterTests > grounded FAILED", T1)));

    String profileId = store.getOrCreateProfile("p").id();
    assertThat(store.graphCounts(profileId).edgeCount()).isPositive();

    List<dev.alvo.pieria.domain.graph.Entity> tests =
      store.findEntitiesByName(profileId, List.of("groundingfiltertests"), 10);
    assertThat(tests).isNotEmpty();

    List<Memory> viaGraph = store.findMemoriesByEntities(profileId,
      tests.stream().map(dev.alvo.pieria.domain.graph.Entity::id).toList(), 10);
    assertThat(viaGraph).hasSize(1);

    // A later green run supersedes the failure, and the failure's edges go with it.
    service.ingest("p", "s2", List.of(trace("./gradlew test", TraceStatus.SUCCESS, 0, null, T2)));

    assertThat(store.findMemoriesByEntities(profileId,
      tests.stream().map(dev.alvo.pieria.domain.graph.Entity::id).toList(), 10)).isEmpty();
  }

  @Test
  void anEmptyOrNullBatchIsHarmless() {
    assertThat(service.ingest("p", "s1", List.of())).isEmpty();
    assertThat(service.ingest("p", "s1", null)).isEmpty();
  }
}
