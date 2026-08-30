package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.ingestion.model.VerificationResult;
import dev.alvo.pieria.ingestion.model.VerificationVerdict;
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

/**
 * The assertion the whole feature exists for: the two motivating questions — "how do I run the
 * tests here" and "why did that test fail" — reach trace-derived memories through the channels
 * recall actually uses. The unit tests around each stage prove the pieces; this proves the path.
 */
class TraceRecallReachabilityTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  @TempDir
  Path tempDir;

  private MemoryStore store;
  private String profileId;

  /**
   * Returns one fixed recipe so the instruction half is exercised without a live model. It also
   * answers verification: a generalized recipe is a paraphrase of the trace log by construction, so
   * it never clears the grounding pre-filter, and a stand-in that left {@code verifyAll} to the
   * interface default would drop every statement before it reached the store.
   */
  private static final class RecipeGateway implements ModelGateway {
    @Override
    public List<TraceRecipe> extractTraceRecipes(String traceLog) {
      return List.of(new TraceRecipe("./gradlew test",
        "Tests in this repo are run with ./gradlew test."));
    }

    @Override
    public List<VerificationResult> verifyAll(List<String> contents, String transcript) {
      return contents.stream()
        .map(content -> new VerificationResult(VerificationVerdict.PASS, content, "stub"))
        .toList();
    }

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
    this.store = TraceTestSupport.newSqliteStore(tempDir.resolve("recall.db"));
    TraceIngestionService service = new TraceIngestionService(store, new NoOpCodeIndexStore(),
      new RecipeGateway(), TraceProperties.defaults(), TraceTestSupport.defaultPieriaProperties());

    service.ingest("p", "s1", List.of(new TraceEventDto("Bash", "./gradlew test", "BUILD FAILED",
      TraceStatus.FAILURE, 1, "GroundingFilterTests > grounded FAILED", null, AT)));
    this.profileId = store.getOrCreateProfile("p").id();
  }

  @Test
  void howDoIRunTheTestsReachesTheTraceDerivedInstruction() {
    List<Memory> hits = store.searchMemoriesFts(profileId, "gradlew test", 10);

    assertThat(hits).anyMatch(memory -> memory.type() == MemoryType.INSTRUCTION
      && memory.content().contains("./gradlew test"));
  }

  @Test
  void whyDidTheTestFailReachesTheTraceEvent() {
    List<Memory> hits = store.searchMemoriesFts(profileId, "GroundingFilterTests", 10);

    assertThat(hits).anyMatch(memory -> memory.type() == MemoryType.EVENT
      && memory.content().contains("failed"));
  }

  // The raw role="tool" row is the safety net MessageFtsChannel searches.
  @Test
  void theRawTraceIsReachableThroughTheMessageSafetyNet() {
    assertThat(store.searchMemoriesByMessageFts(profileId, "BUILD FAILED", 10)).isNotEmpty();
  }

  @Test
  void traceMemoriesAreFilterableBySourceAndType() {
    List<Memory> events = store.listMemories(profileId, MemoryType.EVENT, null);

    assertThat(events).isNotEmpty();
    assertThat(events).allMatch(memory -> memory.payload().contains("\"source\":\"trace\""));
  }
}
