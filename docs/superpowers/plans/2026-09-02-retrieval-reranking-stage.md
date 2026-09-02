# Retrieval Reranking Stage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Insert a reranking stage between weighted Reciprocal Rank Fusion and the `limit` truncation in Pieria's recall pipeline, so a higher-precision top-K reaches synthesis (and the agent's context), without ever failing or slowing a recall that works today.

**Architecture:** Two `Reranker` implementations composed in order inside `RetrievalService.fuse(...)`. `SemanticRescorer` is a pure function that blends cosine-to-query into the RRF score using vectors the near-duplicate collapse pass already reads — it runs in every recall tier and costs no model call. `ModelReranker` asks the small/fast model tier for a coarse three-way relevance label per candidate and re-buckets on it — it runs only at `ANALYZED`/`SYNTHESIZED`. Every failure path returns the input list unchanged.

**Tech Stack:** Java 25 (records, virtual threads), Spring Boot 4.0.6, Spring AI 2.0.0-M6, JUnit 5 + AssertJ, Gradle Kotlin DSL. Vanilla ES modules for the console.

**Spec:** `docs/superpowers/specs/2026-09-02-retrieval-reranking-design.md` — read it before starting. Decisions are referenced below as D1-D7; the spec explains *why*, this plan says *how*.

## Global Constraints

- **Java 25**, Spring Boot 4.0.6. Test classes use the `*Tests` suffix. Prefer narrow unit/slice tests over `@SpringBootTest`.
- **Never run `./gradlew nativeCompile`, `nativeDist`, or `deployLocal`.** They are slow and `deployLocal` overwrites the user's installed binaries. Verify with `./gradlew test` and `./gradlew :daemon:compileJava`.
- **`./gradlew test` must pass before every commit.**
- **No test seams in production code.** No public/injectable fields, no `null`-fallback overrides that exist only so a test can swap an implementation. Test through the real public surface with the existing `FakeModelGateway` and the in-file store fakes.
- **The reranker must never fail a recall.** Every error, timeout, misalignment, or absent signal returns the incoming candidate list unchanged.
- **`RecallCandidate.score` keeps carrying the RRF score.** The rerank verdict is expressed in list order and in debug diagnostics only (D2).
- **The blend knob is named `rerank-semantic-weight`, never `weight-rerank-semantic`** (D7) — `channel-mix.js` selects fusion channels with `f.key.indexOf("retrieval.weight-") === 0`, and a `weight-` prefix would put a non-channel in the channel mix bar.
- **Cross-module utility code belongs in `shared`** (`dev.alvo.pieria.tools`), never reimplemented per module. `Vectors.cosine(float[], float[])` already exists there — use it.
- Commit messages follow the repo's Conventional Commits style (`feat(rerank): …`, `test(rerank): …`, `docs(phase-9): …`).

---

## File Structure

**New — `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/`**

| File | Responsibility |
|---|---|
| `Reranker.java` | The seam. `RerankOutcome rerank(RerankInput)`. Never throws, never widens the list. |
| `RerankInput.java` | Immutable input: query, query embedding, candidates, vectors, settings. |
| `RerankSettings.java` | The six tuning values, decoupled from `PieriaProperties`. |
| `RerankOutcome.java` | Result: reordered candidates + this stage's diagnostics. |
| `SemanticRescorer.java` | D5. Cosine-blend re-scoring. Pure function, no I/O, no model. |
| `ModelReranker.java` | D2/D4. Label fetch, bucketing, drop, unanimous-irrelevance guard. |

**New — elsewhere**

| File | Responsibility |
|---|---|
| `retrieval/model/RerankLabel.java` | `ESSENTIAL` / `RELATED` / `IRRELEVANT`, plus lenient wire parsing. |
| `retrieval/RetrievalDiagnostics.java` (modify) | Add the `RerankDiagnostics` record + component. |
| `resources/prompts/rerank-candidates.txt` | The small-tier prompt, compact line protocol. |

**Modified — production**

| File | Change |
|---|---|
| `model/ModelGateway.java` | `default List<RerankLabel> rerankCandidates(String, List<String>)` returning `List.of()`. |
| `model/OpenAiModelGateway.java` | Real implementation over `callExtractionText`. |
| `retrieval/RetrievalService.java:413-419` | `fuse(...)` gains the stage; truncation moves to the end; `embeddingsFor` hoisted. |
| `retrieval/model/RecallCandidate.java` | Javadoc: score is RRF, list order is authoritative. |
| `config/PieriaProperties.java:314-341` | Six `rerank*` components appended to `Retrieval`. |
| `shared/config/model/DaemonOverrides.java` | Six components on `Retrieval`; `isEmpty()` updated. |
| `config/EffectiveConfigResolver.java:82-108` | Six `nvl(...)` lines in `overlayRetrieval`. |
| `config/ProfileConfigService.java:49-71` | Six getters in `toFullOverrides`. |
| `api/controller/ProfileController.java` | Map rerank diagnostics into `RecallDebug`. |
| `shared/api/response/RecallResponse.java` | `RerankDiagnostic` record inside `RecallDebug`. |
| `resources/config/config-schema.json` | 12 entries (6 profile + 6 global), section `rerank`. |
| `resources/config/pieria-default-config.toml` | Six commented defaults under `[pieria.retrieval]`. |
| `resources/static/js/console/config/profile.js` | `SECTION_TITLES` entry; section-inactive predicate. |
| `resources/static/js/console/config/global.js` | Header comment counts. |

**Modified — tests.** 14 positional `PieriaProperties.Retrieval` / `DaemonOverrides.Retrieval` constructor call sites across 12 files (Task 5 lists them all).

---

## Task 1: The rerank contract

Defines the vocabulary every later task consumes: the label enum and the gateway method. Nothing calls it yet.

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/model/RerankLabel.java`
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/model/ModelGateway.java`
- Modify: `modules/daemon/src/test/java/dev/alvo/pieria/model/FakeModelGateway.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/retrieval/model/RerankLabelTests.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `RerankLabel.{ESSENTIAL, RELATED, IRRELEVANT}`; `RerankLabel.fromWire(String) -> RerankLabel` (never null, unknown input yields `RELATED`); `ModelGateway.rerankCandidates(String query, List<String> contents) -> List<RerankLabel>`; `FakeModelGateway.setRerankLabels(List<RerankLabel>)`, `FakeModelGateway.rerankCalls` (int), `FakeModelGateway.rerankedContents` (`List<List<String>>`).

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/retrieval/model/RerankLabelTests.java`:

```java
package dev.alvo.pieria.retrieval.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire form is what a small model types, so parsing must be forgiving in one direction only:
 * anything unrecognised becomes RELATED, which reorders nothing and drops nothing.
 */
class RerankLabelTests {

  @Test
  void parsesTheThreeLabelsCaseAndWhitespaceInsensitively() {
    assertThat(RerankLabel.fromWire("essential")).isEqualTo(RerankLabel.ESSENTIAL);
    assertThat(RerankLabel.fromWire("  ESSENTIAL ")).isEqualTo(RerankLabel.ESSENTIAL);
    assertThat(RerankLabel.fromWire("Related")).isEqualTo(RerankLabel.RELATED);
    assertThat(RerankLabel.fromWire("IRRELEVANT")).isEqualTo(RerankLabel.IRRELEVANT);
  }

  @Test
  void unknownNullAndBlankAllBecomeRelated() {
    assertThat(RerankLabel.fromWire("banana")).isEqualTo(RerankLabel.RELATED);
    assertThat(RerankLabel.fromWire(null)).isEqualTo(RerankLabel.RELATED);
    assertThat(RerankLabel.fromWire("   ")).isEqualTo(RerankLabel.RELATED);
    assertThat(RerankLabel.fromWire("9")).isEqualTo(RerankLabel.RELATED);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.retrieval.model.RerankLabelTests"`
Expected: FAIL — compilation error, `RerankLabel` does not exist.

- [ ] **Step 3: Write the enum**

Create `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/model/RerankLabel.java`:

```java
package dev.alvo.pieria.retrieval.model;

import java.util.Locale;

/**
 * How relevant one retrieval candidate is to the query, as judged by the small/fast model tier.
 *
 * <p>Deliberately a three-way label rather than a numeric score: the small tier is a local ~4-8B
 * model, which clusters numeric relevance judgements so tightly (nearly everything lands on 7 or 8)
 * that the resulting order is noise. A coarse label is a judgement that model class makes reliably,
 * and it modulates the channel evidence RRF earned rather than replacing it.
 */
public enum RerankLabel {

  /** Directly answers the query; ranked above everything merely related. */
  ESSENTIAL,

  /** Plausibly useful context. The neutral verdict, and the one every parse failure degrades to. */
  RELATED,

  /** Does not bear on the query; dropped, unless every candidate said the same (see ModelReranker). */
  IRRELEVANT;

  /**
   * Parse one model-emitted token. Forgiving in exactly one direction: anything unrecognised — a
   * typo, a number, a null, an empty line — becomes {@link #RELATED}, so a garbled response
   * reorders nothing and drops nothing rather than dropping the wrong thing.
   */
  public static RerankLabel fromWire(String token) {
    if (token == null) {
      return RELATED;
    }
    return switch (token.strip().toLowerCase(Locale.ROOT)) {
      case "essential" -> ESSENTIAL;
      case "irrelevant" -> IRRELEVANT;
      default -> RELATED;
    };
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.retrieval.model.RerankLabelTests"`
Expected: PASS

- [ ] **Step 5: Add the gateway method**

In `modules/daemon/src/main/java/dev/alvo/pieria/model/ModelGateway.java`, add the import `dev.alvo.pieria.retrieval.model.RerankLabel` alongside the existing `dev.alvo.pieria.retrieval.model.*` imports, and add this method directly after `analyzeQuery(...)`:

```java
  /**
   * Rerank: label each candidate's relevance to {@code query}, in one batched call on the
   * small/fast model tier. Never the large synthesis model.
   *
   * <p>Additive and degradable, like {@link #extractGraph}: the default returns an empty list —
   * "no signal" — so stubs and gateways without rerank support keep working, and callers must treat
   * that as "leave the fused order alone".
   *
   * <p>A non-empty result MUST be aligned 1:1 with {@code contents} (same order, same size).
   * Callers treat any other size as failure and fall back, for the same reason
   * {@link #embedAll} must not return a short list: a label attached to the wrong candidate is a
   * silent wrong drop with nothing to signal it happened.
   */
  default List<RerankLabel> rerankCandidates(String query, List<String> contents) {
    return List.of();
  }
```

- [ ] **Step 6: Write the failing test for the default and the fake**

Create `modules/daemon/src/test/java/dev/alvo/pieria/model/ModelGatewayRerankTests.java`:

```java
package dev.alvo.pieria.model;

import dev.alvo.pieria.retrieval.model.RerankLabel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rerank method must be additive: a gateway that knows nothing about reranking still compiles
 * and still reports "no signal" rather than throwing.
 */
class ModelGatewayRerankTests {

  @Test
  void defaultImplementationReportsNoSignal() {
    ModelGateway bare = new ModelGateway() {
      @Override
      public String synthesizeRecall(String query, List<dev.alvo.pieria.retrieval.model.RecallCandidate> candidates) {
        return "";
      }

      @Override
      public float[] embed(String text) {
        return new float[0];
      }
    };

    assertThat(bare.rerankCandidates("q", List.of("a", "b"))).isEmpty();
  }

  @Test
  void fakeReturnsConfiguredLabelsAndRecordsTheCall() {
    FakeModelGateway fake = new FakeModelGateway();
    fake.setRerankLabels(List.of(RerankLabel.ESSENTIAL, RerankLabel.IRRELEVANT));

    List<RerankLabel> labels = fake.rerankCandidates("q", List.of("a", "b"));

    assertThat(labels).containsExactly(RerankLabel.ESSENTIAL, RerankLabel.IRRELEVANT);
    assertThat(fake.rerankCalls).isEqualTo(1);
    assertThat(fake.rerankedContents).containsExactly(List.of("a", "b"));
  }

  @Test
  void fakeReportsNoSignalUntilLabelsAreConfigured() {
    FakeModelGateway fake = new FakeModelGateway();

    assertThat(fake.rerankCandidates("q", List.of("a"))).isEmpty();
    assertThat(fake.rerankCalls).isEqualTo(1);
  }

  @Test
  void fakeHonoursUnavailable() {
    FakeModelGateway fake = new FakeModelGateway();
    fake.setRerankLabels(List.of(RerankLabel.ESSENTIAL));
    fake.setUnavailable(true);

    org.assertj.core.api.Assertions
      .assertThatThrownBy(() -> fake.rerankCandidates("q", List.of("a")))
      .isInstanceOf(ModelUnavailableException.class);
  }
}
```

- [ ] **Step 7: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.model.ModelGatewayRerankTests"`
Expected: FAIL — `setRerankLabels`, `rerankCalls`, `rerankedContents` do not exist on `FakeModelGateway`.

- [ ] **Step 8: Extend `FakeModelGateway`**

In `modules/daemon/src/test/java/dev/alvo/pieria/model/FakeModelGateway.java`, add the import `dev.alvo.pieria.retrieval.model.RerankLabel`, then add these members next to the existing `summarizeCalls` field:

```java
  /**
   * Every {@link #rerankCandidates} content list, in call order — lets tests count model calls
   * (proving EVIDENCE recalls make none) and inspect exactly what the prompt would have been given.
   */
  public final List<List<String>> rerankedContents = new java.util.ArrayList<>();

  /** How many times {@link #rerankCandidates} was called. */
  public int rerankCalls;

  private List<RerankLabel> rerankLabels = List.of();

  /**
   * Labels the next {@link #rerankCandidates} call returns. Left unset, the fake reports "no
   * signal" (an empty list), which is the pass-through case.
   */
  public void setRerankLabels(List<RerankLabel> labels) {
    this.rerankLabels = labels == null ? List.of() : List.copyOf(labels);
  }

  @Override
  public List<RerankLabel> rerankCandidates(String query, List<String> contents) {
    rerankCalls++;
    rerankedContents.add(contents == null ? List.of() : List.copyOf(contents));
    failIfUnavailable();
    return rerankLabels;
  }
```

Note the ordering: the call is recorded *before* `failIfUnavailable()`, so a test asserting "the model was consulted and it blew up" can see both facts.

- [ ] **Step 9: Run tests to verify they pass**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.model.ModelGatewayRerankTests" --tests "dev.alvo.pieria.retrieval.model.RerankLabelTests"`
Expected: PASS (all 6 tests)

- [ ] **Step 10: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/retrieval/model/RerankLabel.java \
        modules/daemon/src/main/java/dev/alvo/pieria/model/ModelGateway.java \
        modules/daemon/src/test/java/dev/alvo/pieria/model/FakeModelGateway.java \
        modules/daemon/src/test/java/dev/alvo/pieria/model/ModelGatewayRerankTests.java \
        modules/daemon/src/test/java/dev/alvo/pieria/retrieval/model/RerankLabelTests.java
git commit -m "feat(rerank): add the coarse relevance label and gateway contract"
```

---

## Task 2: The seam and the deterministic re-scorer

The half that runs in every tier, including `EVIDENCE`. Pure arithmetic over data the pipeline already holds (D5).

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/Reranker.java`
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/RerankSettings.java`
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/RerankInput.java`
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/RerankOutcome.java`
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/SemanticRescorer.java`
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/RetrievalDiagnostics.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/retrieval/rerank/SemanticRescorerTests.java`

**Interfaces:**
- Consumes: `RecallCandidate(Memory memory, double score, String source)`; `Vectors.cosine(float[], float[]) -> double` from `dev.alvo.pieria.tools`.
- Produces: `Reranker.rerank(RerankInput) -> RerankOutcome`; `RerankSettings(boolean enabled, double semanticWeight, boolean modelEnabled, int window, int snippetChars, long timeoutMs)`; `RerankInput(String query, float[] queryEmbedding, List<RecallCandidate> candidates, Map<String,float[]> vectors, RerankSettings settings)`; `RerankOutcome(List<RecallCandidate> candidates, RetrievalDiagnostics.RerankDiagnostics diagnostics)`; `RerankOutcome.passThrough(List<RecallCandidate>, String stage, long latencyMs)`; `RetrievalDiagnostics.RerankDiagnostics(String stage, int input, int output, int dropped, long latencyMs, boolean fellBack)`.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/retrieval/rerank/SemanticRescorerTests.java`:

```java
package dev.alvo.pieria.retrieval.rerank;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The re-scorer is the half that runs in every recall tier, so it must be pure arithmetic: no
 * store, no model, no clock. These tests pin the blend, the missing-vector exemption, and the
 * determinism the pipeline downstream assumes.
 */
class SemanticRescorerTests {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private final SemanticRescorer rescorer = new SemanticRescorer();

  private static Memory mem(String id) {
    return new Memory(id, "s1", MemoryType.FACT, "content of " + id, "topic." + id,
      null, false, "{}", null, T0);
  }

  private static RecallCandidate candidate(String id, double score) {
    return new RecallCandidate(mem(id), score, "fts");
  }

  /** A unit vector pointing along axis {@code axis} of a 4-dimensional space. */
  private static float[] axis(int axis) {
    float[] vector = new float[4];
    vector[axis] = 1.0f;
    return vector;
  }

  private static RerankSettings settings(double semanticWeight) {
    return new RerankSettings(true, semanticWeight, true, 30, 400, 4000L);
  }

  private RerankOutcome rescore(List<RecallCandidate> candidates, Map<String, float[]> vectors,
                                float[] query, double weight) {
    return rescorer.rerank(new RerankInput("q", query, candidates, vectors, settings(weight)));
  }

  @Test
  void semanticSignalPromotesALowerRrfCandidate() {
    // "b" is ranked below "a" by RRF but points exactly at the query; "a" is orthogonal to it.
    List<RecallCandidate> input = List.of(candidate("a", 0.9), candidate("b", 0.1));
    Map<String, float[]> vectors = Map.of("a", axis(1), "b", axis(0));

    RerankOutcome outcome = rescore(input, vectors, axis(0), 0.6);

    assertThat(outcome.candidates()).extracting(c -> c.memory().id()).containsExactly("b", "a");
  }

  @Test
  void rrfStillWinsWhenTheSemanticWeightIsSmall() {
    List<RecallCandidate> input = List.of(candidate("a", 0.9), candidate("b", 0.1));
    Map<String, float[]> vectors = Map.of("a", axis(1), "b", axis(0));

    RerankOutcome outcome = rescore(input, vectors, axis(0), 0.2);

    assertThat(outcome.candidates()).extracting(c -> c.memory().id()).containsExactly("a", "b");
  }

  @Test
  void candidatesWithoutAVectorKeepTheirRrfPositionRatherThanScoringZero() {
    // "t" models a task memory: deliberately never embedded. It must not be pushed below a
    // candidate that merely happens to have a vector, or recall quality tracks indexer backlog.
    List<RecallCandidate> input = List.of(candidate("t", 0.9), candidate("b", 0.5));
    Map<String, float[]> vectors = Map.of("b", axis(1));

    RerankOutcome outcome = rescore(input, vectors, axis(0), 0.6);

    assertThat(outcome.candidates()).extracting(c -> c.memory().id()).containsExactly("t", "b");
  }

  @Test
  void scoresAreLeftCarryingTheRrfValue() {
    List<RecallCandidate> input = List.of(candidate("a", 0.9), candidate("b", 0.1));
    Map<String, float[]> vectors = Map.of("a", axis(1), "b", axis(0));

    RerankOutcome outcome = rescore(input, vectors, axis(0), 0.6);

    assertThat(outcome.candidates()).extracting(RecallCandidate::score).containsExactly(0.1, 0.9);
    assertThat(outcome.candidates()).extracting(RecallCandidate::source).containsOnly("fts");
  }

  @Test
  void zeroWeightIsIdentity() {
    List<RecallCandidate> input = List.of(candidate("a", 0.9), candidate("b", 0.1));
    Map<String, float[]> vectors = Map.of("a", axis(1), "b", axis(0));

    RerankOutcome outcome = rescore(input, vectors, axis(0), 0.0);

    assertThat(outcome.candidates()).isEqualTo(input);
    assertThat(outcome.diagnostics().fellBack()).isTrue();
  }

  @Test
  void noQueryEmbeddingIsIdentity() {
    List<RecallCandidate> input = List.of(candidate("a", 0.9), candidate("b", 0.1));

    RerankOutcome outcome = rescore(input, Map.of("a", axis(0)), null, 0.6);

    assertThat(outcome.candidates()).isEqualTo(input);
    assertThat(outcome.diagnostics().fellBack()).isTrue();
  }

  @Test
  void emptyAndSingletonInputsPassThroughUnchanged() {
    assertThat(rescore(List.of(), Map.of(), axis(0), 0.6).candidates()).isEmpty();

    List<RecallCandidate> one = List.of(candidate("a", 0.9));
    assertThat(rescore(one, Map.of("a", axis(0)), axis(0), 0.6).candidates()).isEqualTo(one);
  }

  @Test
  void negativeCosineIsClampedRatherThanSubtracting() {
    // An opposed vector must score the same as no similarity at all, never worse than a candidate
    // with no vector — clamping is what keeps the two comparable.
    float[] opposed = new float[] {-1.0f, 0.0f, 0.0f, 0.0f};
    List<RecallCandidate> input = List.of(candidate("a", 0.5), candidate("b", 0.4));
    Map<String, float[]> vectors = Map.of("a", opposed);

    RerankOutcome outcome = rescore(input, vectors, axis(0), 0.6);

    assertThat(outcome.candidates()).extracting(c -> c.memory().id()).containsExactly("a", "b");
  }

  @Test
  void isDeterministicForAFixedInput() {
    List<RecallCandidate> input = List.of(candidate("a", 0.5), candidate("b", 0.5), candidate("c", 0.5));
    Map<String, float[]> vectors = Map.of("a", axis(0), "b", axis(0), "c", axis(0));

    List<String> first = rescore(input, vectors, axis(0), 0.6).candidates()
      .stream().map(c -> c.memory().id()).toList();
    List<String> second = rescore(input, vectors, axis(0), 0.6).candidates()
      .stream().map(c -> c.memory().id()).toList();

    assertThat(first).isEqualTo(second).containsExactly("a", "b", "c");
  }

  @Test
  void reportsItsOwnDiagnostics() {
    List<RecallCandidate> input = List.of(candidate("a", 0.9), candidate("b", 0.1));

    RerankOutcome outcome = rescore(input, Map.of("a", axis(0), "b", axis(0)), axis(0), 0.6);

    assertThat(outcome.diagnostics().stage()).isEqualTo("semantic");
    assertThat(outcome.diagnostics().input()).isEqualTo(2);
    assertThat(outcome.diagnostics().output()).isEqualTo(2);
    assertThat(outcome.diagnostics().dropped()).isZero();
    assertThat(outcome.diagnostics().fellBack()).isFalse();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.retrieval.rerank.SemanticRescorerTests"`
Expected: FAIL — compilation errors, none of the `rerank` package types exist.

- [ ] **Step 3: Add the diagnostics record**

In `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/RetrievalDiagnostics.java`, change the record header and add the nested record. The full file becomes:

```java
package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;

import java.util.List;

/**
 * Per-recall retrieval diagnostics: one entry per channel with its latency, hit
 * count, and whether it failed, plus the analysis that drove the channels, plus one entry per
 * rerank sub-stage that ran. Collected only when the caller asks for debug output; default API
 * responses stay concise.
 *
 * @param analysis the query analysis used for this recall
 * @param channels per-channel timing/hit/failure records
 * @param rerank   per-rerank-stage records, in the order the stages ran
 */
public record RetrievalDiagnostics(QueryAnalysis analysis,
                                   List<ChannelDiagnostics> channels,
                                   List<RerankDiagnostics> rerank) {

  public RetrievalDiagnostics {
    channels = channels == null ? List.of() : List.copyOf(channels);
    rerank = rerank == null ? List.of() : List.copyOf(rerank);
  }

  /**
   * @param channel   the channel
   * @param latencyMs wall-clock time the channel took
   * @param hits      number of candidates it returned
   * @param failed    whether it failed/timed out (only possible for non-critical channels)
   */
  public record ChannelDiagnostics(RetrievalChannelType channel, long latencyMs, int hits, boolean failed) {
  }

  /**
   * One rerank sub-stage's outcome.
   *
   * <p>{@code fellBack} is the field to read when a rerank looks like it did nothing: it means the
   * stage declined to act — disabled, no query embedding, no model signal, a timeout, misaligned
   * labels, or unanimous irrelevance — and returned its input untouched. That is the designed
   * behaviour, not an error, but it is invisible from the candidate list alone.
   *
   * @param stage     which sub-stage: {@code "semantic"} or {@code "model"}
   * @param input     candidates handed to the stage
   * @param output    candidates it returned
   * @param dropped   candidates it removed ({@code input - output})
   * @param latencyMs wall-clock time the stage took
   * @param fellBack  whether the stage passed its input through unchanged
   */
  public record RerankDiagnostics(String stage, int input, int output, int dropped,
                                  long latencyMs, boolean fellBack) {
  }
}
```

- [ ] **Step 4: Write the rerank package types**

Create `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/RerankSettings.java`:

```java
package dev.alvo.pieria.retrieval.rerank;

/**
 * The rerank stage's tuning, lifted out of {@code PieriaProperties.Retrieval} so this package does
 * not depend on Spring configuration binding and stays unit-testable with plain values.
 *
 * @param enabled        master switch over BOTH sub-stages; false means the pipeline behaves
 *                       exactly as it did before the stage existed
 * @param semanticWeight the cosine term's share of the blended score, in [0,1]; 0 disables the
 *                       deterministic re-scorer alone
 * @param modelEnabled   whether the model reranker runs (it additionally requires a recall tier
 *                       that already does model analysis)
 * @param window         how many fused candidates the stage considers
 * @param snippetChars   per-candidate character bound on the text handed to the model
 * @param timeoutMs      wall-clock bound on the model call
 */
public record RerankSettings(boolean enabled,
                             double semanticWeight,
                             boolean modelEnabled,
                             int window,
                             int snippetChars,
                             long timeoutMs) {

  public RerankSettings {
    semanticWeight = Math.clamp(semanticWeight, 0.0, 1.0);
    window = Math.max(0, window);
    snippetChars = Math.max(1, snippetChars);
    timeoutMs = Math.max(1L, timeoutMs);
  }
}
```

Create `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/RerankInput.java`:

```java
package dev.alvo.pieria.retrieval.rerank;

import dev.alvo.pieria.retrieval.model.RecallCandidate;

import java.util.List;
import java.util.Map;

/**
 * Everything a {@link Reranker} needs for one recall. Deliberately carries the already-fetched
 * {@code vectors} rather than a store handle: the near-duplicate collapse pass reads them anyway,
 * so passing the map through is what keeps the deterministic re-scorer free of I/O.
 *
 * @param query          the raw recall query
 * @param queryEmbedding the query's vector, or {@code null} when vector search is off or embedding
 *                       failed — which makes the semantic sub-stage a pass-through
 * @param candidates     the fused, collapsed candidates, in RRF order
 * @param vectors        memory id → stored embedding, for whichever candidates have one
 * @param settings       the tuning for this recall's profile
 */
public record RerankInput(String query,
                          float[] queryEmbedding,
                          List<RecallCandidate> candidates,
                          Map<String, float[]> vectors,
                          RerankSettings settings) {

  public RerankInput {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
    vectors = vectors == null ? Map.of() : Map.copyOf(vectors);
  }
}
```

Create `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/RerankOutcome.java`:

```java
package dev.alvo.pieria.retrieval.rerank;

import dev.alvo.pieria.retrieval.RetrievalDiagnostics.RerankDiagnostics;
import dev.alvo.pieria.retrieval.model.RecallCandidate;

import java.util.List;

/**
 * What one rerank sub-stage produced: the (possibly reordered, possibly shortened) candidates and
 * the record of what it did.
 */
public record RerankOutcome(List<RecallCandidate> candidates, RerankDiagnostics diagnostics) {

  public RerankOutcome {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
  }

  /**
   * The stage declined to act and handed its input straight back. This is the shape of every
   * failure, every disabled path, and every absent signal — a reranker never has another way to
   * fail.
   */
  public static RerankOutcome passThrough(List<RecallCandidate> candidates, String stage, long latencyMs) {
    int size = candidates == null ? 0 : candidates.size();
    return new RerankOutcome(candidates, new RerankDiagnostics(stage, size, size, 0, latencyMs, true));
  }
}
```

Create `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/Reranker.java`:

```java
package dev.alvo.pieria.retrieval.rerank;

/**
 * Re-orders (and may shorten) the fused candidates before the recall {@code limit} is applied.
 *
 * <p>Two contracts, and they are absolute. An implementation must never throw — a reranker is a
 * precision improvement, and a recall that worked without one must not start failing because one
 * was added. And it must never widen the list it received: a reranker ranks candidates, it does not
 * retrieve them.
 *
 * <p>This is also the seam a dedicated reranker model (a bge-reranker over Ollama, say) would slot
 * into as a third implementation, without the pipeline learning anything new.
 */
public interface Reranker {

  RerankOutcome rerank(RerankInput input);
}
```

- [ ] **Step 5: Write the re-scorer**

Create `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/SemanticRescorer.java`:

```java
package dev.alvo.pieria.retrieval.rerank;

import dev.alvo.pieria.retrieval.RetrievalDiagnostics.RerankDiagnostics;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.tools.Vectors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Blends cosine-to-query into the fused RRF score, so candidates from channels with incomparable
 * native scales compete on one axis.
 *
 * <p>The problem this solves: an exact-key or FTS hit arrives with no semantic score at all — it
 * matched a string. RRF ranks it against vector hits by rank position alone, which says nothing
 * about whether it is *about* the query. One cosine per candidate, against vectors the collapse
 * pass has already read, gives every candidate a comparable relevance term.
 *
 * <p>Pure arithmetic: no store, no model, no clock. That is what lets it run in the EVIDENCE tier,
 * whose contract is the query embedding and nothing else.
 */
public class SemanticRescorer implements Reranker {

  static final String STAGE = "semantic";

  /**
   * A candidate with no stored vector keeps its normalized RRF score untouched.
   *
   * <p>{@code task} memories are deliberately never embedded, and a freshly ingested memory may
   * still be sitting in the vectorization outbox. Neither is evidence of irrelevance, so scoring
   * them 0 would make recall quality a function of indexer backlog rather than of the query.
   */
  private static double blend(double normalizedRrf, float[] queryEmbedding, float[] memoryVector,
                              double weight) {
    if (memoryVector == null || memoryVector.length != queryEmbedding.length) {
      return normalizedRrf;
    }
    // Clamped, not signed: an opposed vector must read as "no similarity", never as worse than a
    // candidate that has no vector to compare at all.
    double cosine = Math.max(0.0, Vectors.cosine(queryEmbedding, memoryVector));
    return (1.0 - weight) * normalizedRrf + weight * cosine;
  }

  @Override
  public RerankOutcome rerank(RerankInput input) {
    long start = System.nanoTime();
    List<RecallCandidate> candidates = input.candidates();
    RerankSettings settings = input.settings();

    if (!settings.enabled() || settings.semanticWeight() <= 0.0
      || input.queryEmbedding() == null || candidates.size() < 2) {
      return RerankOutcome.passThrough(candidates, STAGE, elapsedMillis(start));
    }

    Map<String, float[]> vectors = input.vectors();
    double weight = settings.semanticWeight();
    float[] query = input.queryEmbedding();

    double min = candidates.stream().mapToDouble(RecallCandidate::score).min().orElse(0.0);
    double max = candidates.stream().mapToDouble(RecallCandidate::score).max().orElse(0.0);
    double span = max - min;

    List<Scored> scored = new ArrayList<>(candidates.size());
    for (int i = 0; i < candidates.size(); i++) {
      RecallCandidate candidate = candidates.get(i);
      // An all-equal window has no spread to normalize; treating every candidate as 1.0 keeps the
      // cosine term as the only discriminator rather than dividing by zero.
      double normalized = span <= 0.0 ? 1.0 : (candidate.score() - min) / span;
      double blended = blend(normalized, query, vectors.get(candidate.memory().id()), weight);
      scored.add(new Scored(candidate, blended, i));
    }

    // Incoming order breaks ties, which is what carries fusion's deterministic recency-then-id
    // tie-break through this stage rather than re-deriving it.
    scored.sort(Comparator.comparingDouble(Scored::score).reversed()
      .thenComparingInt(Scored::originalIndex));

    List<RecallCandidate> reordered = scored.stream().map(Scored::candidate).toList();
    int size = reordered.size();
    return new RerankOutcome(reordered,
      new RerankDiagnostics(STAGE, candidates.size(), size, 0, elapsedMillis(start), false));
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  private record Scored(RecallCandidate candidate, double score, int originalIndex) {
  }
}
```

- [ ] **Step 6: Fix the one existing `RetrievalDiagnostics` construction**

`RetrievalService.java:285` constructs `new RetrievalDiagnostics(analysis.value(), channelDiagnostics)`. Add the third argument so the module compiles; Task 6 replaces it with the real list:

```java
      RetrievalDiagnostics diagnostics = debug
        ? new RetrievalDiagnostics(analysis.value(), channelDiagnostics, List.of())
        : null;
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.retrieval.rerank.SemanticRescorerTests"`
Expected: PASS (10 tests)

- [ ] **Step 8: Run the full suite**

Run: `./gradlew test`
Expected: PASS. If `RetrievalServiceTests` fails on a diagnostics assertion, it is the record-shape change from Step 3 — update the assertion to expect an empty `rerank()` list, not the old two-argument shape.

- [ ] **Step 9: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/ \
        modules/daemon/src/main/java/dev/alvo/pieria/retrieval/RetrievalDiagnostics.java \
        modules/daemon/src/main/java/dev/alvo/pieria/retrieval/RetrievalService.java \
        modules/daemon/src/test/java/dev/alvo/pieria/retrieval/rerank/
git commit -m "feat(rerank): add the reranker seam and the deterministic cosine re-scorer"
```

---

## Task 3: The model reranker

The half that runs at `ANALYZED`+ (D2, D4). Owns labelling, bucketing, and every degradation except the timeout, which its caller owns.

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/ModelReranker.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/retrieval/rerank/ModelRerankerTests.java`

**Interfaces:**
- Consumes: `Reranker`, `RerankInput`, `RerankOutcome`, `RerankSettings` (Task 2); `ModelGateway.rerankCandidates`, `RerankLabel` (Task 1).
- Produces: `new ModelReranker(ModelGateway)`; `ModelReranker.STAGE == "model"`.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/retrieval/rerank/ModelRerankerTests.java`:

```java
package dev.alvo.pieria.retrieval.rerank;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.RerankLabel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model reranker's job is to be useful when the model cooperates and invisible when it does
 * not. Most of these tests are about the second half.
 */
class ModelRerankerTests {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static Memory mem(String id) {
    return new Memory(id, "s1", MemoryType.FACT, "content of " + id, "topic." + id,
      null, false, "{}", null, T0);
  }

  private static RecallCandidate candidate(String id) {
    return new RecallCandidate(mem(id), 1.0, "fts");
  }

  private static RerankSettings settings() {
    return new RerankSettings(true, 0.4, true, 30, 400, 4000L);
  }

  private static RerankInput input(List<RecallCandidate> candidates) {
    return new RerankInput("q", null, candidates, Map.of(), settings());
  }

  private static List<String> ids(RerankOutcome outcome) {
    return outcome.candidates().stream().map(c -> c.memory().id()).toList();
  }

  @Test
  void essentialOutranksRelatedAndIrrelevantIsDropped() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.RELATED, RerankLabel.IRRELEVANT, RerankLabel.ESSENTIAL));
    ModelReranker reranker = new ModelReranker(model);

    RerankOutcome outcome = reranker.rerank(
      input(List.of(candidate("a"), candidate("b"), candidate("c"))));

    assertThat(ids(outcome)).containsExactly("c", "a");
    assertThat(outcome.diagnostics().stage()).isEqualTo("model");
    assertThat(outcome.diagnostics().input()).isEqualTo(3);
    assertThat(outcome.diagnostics().output()).isEqualTo(2);
    assertThat(outcome.diagnostics().dropped()).isEqualTo(1);
    assertThat(outcome.diagnostics().fellBack()).isFalse();
  }

  @Test
  void incomingOrderIsPreservedWithinABucket() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(
      RerankLabel.ESSENTIAL, RerankLabel.RELATED, RerankLabel.ESSENTIAL, RerankLabel.RELATED));
    ModelReranker reranker = new ModelReranker(model);

    RerankOutcome outcome = reranker.rerank(
      input(List.of(candidate("a"), candidate("b"), candidate("c"), candidate("d"))));

    assertThat(ids(outcome)).containsExactly("a", "c", "b", "d");
  }

  @Test
  void scoreAndProvenanceSurviveTheStage() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.RELATED, RerankLabel.ESSENTIAL));
    ModelReranker reranker = new ModelReranker(model);

    RerankOutcome outcome = reranker.rerank(input(List.of(
      new RecallCandidate(mem("a"), 0.91, "exact-key"),
      new RecallCandidate(mem("b"), 0.42, "fts+vector"))));

    assertThat(outcome.candidates().getFirst().score()).isEqualTo(0.42);
    assertThat(outcome.candidates().getFirst().source()).isEqualTo("fts+vector");
    assertThat(outcome.candidates().getLast().source()).isEqualTo("exact-key");
  }

  @Test
  void unanimousIrrelevanceIsTreatedAsNoSignalNotAsAVerdict() {
    // A model that calls everything irrelevant is indistinguishable from a broken one, and
    // honouring it would silently blank a recall RRF had right.
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.IRRELEVANT, RerankLabel.IRRELEVANT));
    ModelReranker reranker = new ModelReranker(model);

    List<RecallCandidate> candidates = List.of(candidate("a"), candidate("b"));
    RerankOutcome outcome = reranker.rerank(input(candidates));

    assertThat(outcome.candidates()).isEqualTo(candidates);
    assertThat(outcome.diagnostics().fellBack()).isTrue();
    assertThat(outcome.diagnostics().dropped()).isZero();
  }

  @Test
  void misalignedLabelCountIsFailureNotPartialData() {
    // A label attached to the wrong candidate is a silent wrong drop, so a short list is refused
    // outright rather than applied to the prefix.
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.IRRELEVANT));
    ModelReranker reranker = new ModelReranker(model);

    List<RecallCandidate> candidates = List.of(candidate("a"), candidate("b"), candidate("c"));
    RerankOutcome outcome = reranker.rerank(input(candidates));

    assertThat(outcome.candidates()).isEqualTo(candidates);
    assertThat(outcome.diagnostics().fellBack()).isTrue();
  }

  @Test
  void emptyLabelsAreNoSignal() {
    FakeModelGateway model = new FakeModelGateway();
    ModelReranker reranker = new ModelReranker(model);

    List<RecallCandidate> candidates = List.of(candidate("a"), candidate("b"));
    RerankOutcome outcome = reranker.rerank(input(candidates));

    assertThat(outcome.candidates()).isEqualTo(candidates);
    assertThat(outcome.diagnostics().fellBack()).isTrue();
  }

  @Test
  void aThrowingModelDoesNotPropagate() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.ESSENTIAL, RerankLabel.IRRELEVANT));
    model.setUnavailable(true);
    ModelReranker reranker = new ModelReranker(model);

    List<RecallCandidate> candidates = List.of(candidate("a"), candidate("b"));
    RerankOutcome outcome = reranker.rerank(input(candidates));

    assertThat(outcome.candidates()).isEqualTo(candidates);
    assertThat(outcome.diagnostics().fellBack()).isTrue();
    assertThat(model.rerankCalls).isEqualTo(1);
  }

  @Test
  void disabledSwitchesSkipTheModelEntirely() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.ESSENTIAL, RerankLabel.IRRELEVANT));
    ModelReranker reranker = new ModelReranker(model);
    List<RecallCandidate> candidates = List.of(candidate("a"), candidate("b"));

    RerankSettings stageOff = new RerankSettings(false, 0.4, true, 30, 400, 4000L);
    RerankSettings modelOff = new RerankSettings(true, 0.4, false, 30, 400, 4000L);

    assertThat(reranker.rerank(new RerankInput("q", null, candidates, Map.of(), stageOff)).candidates())
      .isEqualTo(candidates);
    assertThat(reranker.rerank(new RerankInput("q", null, candidates, Map.of(), modelOff)).candidates())
      .isEqualTo(candidates);
    assertThat(model.rerankCalls).isZero();
  }

  @Test
  void candidateTextIsTruncatedToTheConfiguredBound() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.ESSENTIAL, RerankLabel.RELATED));
    ModelReranker reranker = new ModelReranker(model);

    Memory verbose = new Memory("v", "s1", MemoryType.FACT, "x".repeat(5000), "topic.v",
      null, false, "{}", null, T0);
    RerankSettings tight = new RerankSettings(true, 0.4, true, 30, 120, 4000L);

    reranker.rerank(new RerankInput("q", null,
      List.of(new RecallCandidate(verbose, 1.0, "fts"), candidate("b")), Map.of(), tight));

    assertThat(model.rerankedContents.getFirst().getFirst()).hasSize(120);
  }

  @Test
  void emptyAndSingletonInputsSkipTheModel() {
    FakeModelGateway model = new FakeModelGateway();
    ModelReranker reranker = new ModelReranker(model);

    assertThat(reranker.rerank(input(List.of())).candidates()).isEmpty();
    assertThat(reranker.rerank(input(List.of(candidate("a")))).candidates()).hasSize(1);
    assertThat(model.rerankCalls).isZero();
  }

  @Test
  void modelUnavailableIsSwallowedRatherThanRethrown() {
    FakeModelGateway model = new FakeModelGateway();
    model.setUnavailable(true);
    ModelReranker reranker = new ModelReranker(model);

    // No assertThatThrownBy: the point is that nothing is thrown.
    RerankOutcome outcome = reranker.rerank(input(List.of(candidate("a"), candidate("b"))));

    assertThat(outcome.diagnostics().fellBack()).isTrue();
    assertThat(ModelUnavailableException.class).isNotNull();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.retrieval.rerank.ModelRerankerTests"`
Expected: FAIL — `ModelReranker` does not exist.

- [ ] **Step 3: Write the implementation**

Create `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/ModelReranker.java`:

```java
package dev.alvo.pieria.retrieval.rerank;

import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.retrieval.RetrievalDiagnostics.RerankDiagnostics;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.RerankLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks the small/fast model tier to label each candidate's relevance, then re-buckets on the
 * answer: everything {@code ESSENTIAL} first, then everything {@code RELATED}, with the incoming
 * order preserved inside each bucket and {@code IRRELEVANT} dropped.
 *
 * <p>Preserving the incoming order within a bucket is the whole trick. The model supplies a coarse
 * judgement it can actually make; the channel evidence RRF earned supplies the fine ordering the
 * model cannot. Neither is asked to do the other's job.
 *
 * <p>This class owns every failure mode except the timeout, which belongs to the caller that runs
 * it on a bounded thread. All of them land in the same place: hand the input back untouched.
 */
public class ModelReranker implements Reranker {

  static final String STAGE = "model";

  private static final Logger LOGGER = LoggerFactory.getLogger(ModelReranker.class);

  private final ModelGateway modelGateway;

  public ModelReranker(ModelGateway modelGateway) {
    this.modelGateway = modelGateway;
  }

  private static String snippet(RecallCandidate candidate, int maxChars) {
    String content = candidate.memory().content();
    if (content == null) {
      return "";
    }
    return content.length() <= maxChars ? content : content.substring(0, maxChars);
  }

  @Override
  public RerankOutcome rerank(RerankInput input) {
    long start = System.nanoTime();
    List<RecallCandidate> candidates = input.candidates();
    RerankSettings settings = input.settings();

    if (!settings.enabled() || !settings.modelEnabled() || candidates.size() < 2) {
      return RerankOutcome.passThrough(candidates, STAGE, elapsedMillis(start));
    }

    List<String> contents = candidates.stream()
      .map(candidate -> snippet(candidate, settings.snippetChars()))
      .toList();

    List<RerankLabel> labels;
    try {
      labels = modelGateway.rerankCandidates(input.query(), contents);
    } catch (RuntimeException e) {
      LOGGER.warn("rerank model call failed ({}); keeping fused order", e.toString());
      return RerankOutcome.passThrough(candidates, STAGE, elapsedMillis(start));
    }

    if (labels == null || labels.isEmpty()) {
      LOGGER.debug("rerank model returned no signal; keeping fused order");
      return RerankOutcome.passThrough(candidates, STAGE, elapsedMillis(start));
    }

    if (labels.size() != candidates.size()) {
      // Not partial data — a wrong-length list means we cannot know which label belongs to which
      // candidate, and applying it to the prefix would drop the wrong memory silently.
      LOGGER.warn("rerank model returned {} label(s) for {} candidate(s); keeping fused order",
        labels.size(), candidates.size());
      return RerankOutcome.passThrough(candidates, STAGE, elapsedMillis(start));
    }

    if (labels.stream().allMatch(label -> label == RerankLabel.IRRELEVANT)) {
      // Pieria treats abstention as correct behaviour, so an empty evidence list is defensible in
      // principle — but a flaky batch that labels everything irrelevant looks identical from here,
      // and honouring it would blank a good recall with nothing to signal it happened.
      LOGGER.warn("rerank model labelled all {} candidate(s) irrelevant; treating as no signal",
        candidates.size());
      return RerankOutcome.passThrough(candidates, STAGE, elapsedMillis(start));
    }

    List<RecallCandidate> essential = new ArrayList<>(candidates.size());
    List<RecallCandidate> related = new ArrayList<>(candidates.size());
    for (int i = 0; i < candidates.size(); i++) {
      switch (labels.get(i)) {
        case ESSENTIAL -> essential.add(candidates.get(i));
        case RELATED -> related.add(candidates.get(i));
        case IRRELEVANT -> { /* dropped */ }
      }
    }

    List<RecallCandidate> reranked = new ArrayList<>(essential.size() + related.size());
    reranked.addAll(essential);
    reranked.addAll(related);

    int dropped = candidates.size() - reranked.size();
    LOGGER.debug("rerank model essential={} related={} dropped={}",
      essential.size(), related.size(), dropped);
    return new RerankOutcome(List.copyOf(reranked),
      new RerankDiagnostics(STAGE, candidates.size(), reranked.size(), dropped,
        elapsedMillis(start), false));
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.retrieval.rerank.ModelRerankerTests"`
Expected: PASS (11 tests)

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/retrieval/rerank/ModelReranker.java \
        modules/daemon/src/test/java/dev/alvo/pieria/retrieval/rerank/ModelRerankerTests.java
git commit -m "feat(rerank): add the small-tier label reranker with pass-through degradation"
```

---

## Task 4: The real gateway implementation

Wires `rerankCandidates` to the extraction-tier chat client using a compact line protocol.

**Files:**
- Create: `modules/daemon/src/main/resources/prompts/rerank-candidates.txt`
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/model/OpenAiModelGateway.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/model/RerankResponseParserTests.java`

**Interfaces:**
- Consumes: `RerankLabel.fromWire` (Task 1); the existing private `callExtractionText(String prompt, String stage)` and `PromptTemplateLoader.render(String, Map)` inside `OpenAiModelGateway`.
- Produces: `OpenAiModelGateway.parseRerankLabels(String raw, int expected) -> List<RerankLabel>` (package-private, for the parser tests).

**Why a line protocol, not structured JSON:** the graph extraction stage already established this pattern (`extract-graph-batch.txt`, `callExtractionText`). A 4-8B local model emits `1 essential` far more reliably than it emits schema-valid JSON, and a malformed line costs one label instead of the whole batch.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/model/RerankResponseParserTests.java`:

```java
package dev.alvo.pieria.model;

import dev.alvo.pieria.retrieval.model.RerankLabel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing is index-addressed rather than positional, because a small model reorders lines, skips
 * some, and occasionally prefixes prose. Anything it fails to say becomes RELATED — the label that
 * changes nothing — and a reply with nothing parseable at all becomes "no signal".
 */
class RerankResponseParserTests {

  @Test
  void parsesOneLabelPerNumberedLine() {
    String raw = """
      1 essential
      2 irrelevant
      3 related
      """;

    assertThat(OpenAiModelGateway.parseRerankLabels(raw, 3))
      .containsExactly(RerankLabel.ESSENTIAL, RerankLabel.IRRELEVANT, RerankLabel.RELATED);
  }

  @Test
  void toleratesSeparatorsBlankLinesAndSurroundingProse() {
    String raw = """
      Here are the labels:

      1: essential
      2 - irrelevant

      3\tessential
      Hope that helps.
      """;

    assertThat(OpenAiModelGateway.parseRerankLabels(raw, 3))
      .containsExactly(RerankLabel.ESSENTIAL, RerankLabel.IRRELEVANT, RerankLabel.ESSENTIAL);
  }

  @Test
  void missingIndicesDefaultToRelated() {
    String raw = "2 essential\n";

    assertThat(OpenAiModelGateway.parseRerankLabels(raw, 3))
      .containsExactly(RerankLabel.RELATED, RerankLabel.ESSENTIAL, RerankLabel.RELATED);
  }

  @Test
  void unknownLabelTokensDefaultToRelated() {
    String raw = "1 critical\n2 irrelevant\n";

    assertThat(OpenAiModelGateway.parseRerankLabels(raw, 2))
      .containsExactly(RerankLabel.RELATED, RerankLabel.IRRELEVANT);
  }

  @Test
  void outOfRangeIndicesAreIgnoredRatherThanShiftingTheRest() {
    String raw = "0 essential\n1 essential\n9 irrelevant\n";

    assertThat(OpenAiModelGateway.parseRerankLabels(raw, 2))
      .containsExactly(RerankLabel.ESSENTIAL, RerankLabel.RELATED);
  }

  @Test
  void aReplyWithNothingParseableIsNoSignal() {
    assertThat(OpenAiModelGateway.parseRerankLabels("I cannot help with that.", 3)).isEmpty();
    assertThat(OpenAiModelGateway.parseRerankLabels("", 3)).isEmpty();
    assertThat(OpenAiModelGateway.parseRerankLabels(null, 3)).isEmpty();
  }

  @Test
  void aDuplicateIndexTakesTheFirstLabel() {
    String raw = "1 essential\n1 irrelevant\n2 related\n";

    assertThat(OpenAiModelGateway.parseRerankLabels(raw, 2))
      .containsExactly(RerankLabel.ESSENTIAL, RerankLabel.RELATED);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.model.RerankResponseParserTests"`
Expected: FAIL — `parseRerankLabels` does not exist.

- [ ] **Step 3: Write the prompt template**

Create `modules/daemon/src/main/resources/prompts/rerank-candidates.txt`:

```
You are ranking stored memories by how well each one answers a question.

Question:
{query}

Candidates:
{candidates}

For each candidate, output exactly one line:

<number> <label>

where <label> is one of:
  essential  - directly answers the question
  related    - useful background, but does not answer it
  irrelevant - does not bear on the question at all

Rules:
- Output one line per candidate, in candidate order. No other text.
- Judge only whether the candidate answers THIS question. Do not judge whether
  the candidate is true, well written, or recent.
- Use "irrelevant" only when the candidate is genuinely off-topic. If you are
  unsure, use "related".

Example for three candidates:
1 essential
2 irrelevant
3 related
```

- [ ] **Step 4: Implement the gateway method**

In `modules/daemon/src/main/java/dev/alvo/pieria/model/OpenAiModelGateway.java`, add the import `dev.alvo.pieria.retrieval.model.RerankLabel`, then add these two methods next to `analyzeQuery`:

```java
  @Override
  public List<RerankLabel> rerankCandidates(String query, List<String> contents) {
    if (query == null || query.isBlank() || contents == null || contents.isEmpty()) {
      return List.of();
    }

    StringBuilder rendered = new StringBuilder();
    for (int i = 0; i < contents.size(); i++) {
      String content = contents.get(i) == null ? "" : contents.get(i).replace('\n', ' ').strip();
      rendered.append(i + 1).append(". ").append(content).append('\n');
    }

    String prompt = PromptTemplateLoader.render("rerank-candidates",
      Map.of("query", query, "candidates", rendered.toString()));

    try {
      return parseRerankLabels(callExtractionText(prompt, "rerank"), contents.size());
    } catch (RuntimeException e) {
      // Reranking is a precision improvement, never a requirement. Report "no signal" and let the
      // caller keep the fused order rather than turning a model hiccup into a failed recall.
      LOGGER.warn("rerank model call failed ({}); reporting no signal", e.toString());
      return List.of();
    }
  }

  /**
   * Parse the rerank reply's line protocol into labels aligned 1:1 with the candidates.
   *
   * <p>Index-addressed, not positional: a small model reorders lines, omits some, and sometimes
   * wraps the list in prose. Every index the reply does not mention keeps {@link RerankLabel#RELATED},
   * the label that reorders nothing and drops nothing, and a reply with no parseable line at all
   * returns an empty list — "no signal" — so a refusal or a wall of prose cannot be mistaken for a
   * considered verdict that everything is merely related.
   */
  static List<RerankLabel> parseRerankLabels(String raw, int expected) {
    if (raw == null || raw.isBlank() || expected <= 0) {
      return List.of();
    }

    RerankLabel[] labels = new RerankLabel[expected];
    boolean parsedAny = false;

    for (String line : raw.split("\\R")) {
      var matcher = RERANK_LINE.matcher(line.strip());
      if (!matcher.matches()) {
        continue;
      }
      int index;
      try {
        index = Integer.parseInt(matcher.group(1));
      } catch (NumberFormatException e) {
        continue;
      }
      if (index < 1 || index > expected || labels[index - 1] != null) {
        continue;
      }
      labels[index - 1] = RerankLabel.fromWire(matcher.group(2));
      parsedAny = true;
    }

    if (!parsedAny) {
      return List.of();
    }

    List<RerankLabel> out = new ArrayList<>(expected);
    for (RerankLabel label : labels) {
      out.add(label == null ? RerankLabel.RELATED : label);
    }
    return List.copyOf(out);
  }
```

Add the pattern constant next to the class's other static fields:

```java
  /** {@code <index><separator><label>} — the rerank reply's line protocol. */
  private static final java.util.regex.Pattern RERANK_LINE =
    java.util.regex.Pattern.compile("^(\\d+)\\s*[.:\\-)]?\\s+([A-Za-z]+)\\s*$");
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.model.RerankResponseParserTests"`
Expected: PASS (7 tests)

- [ ] **Step 6: Verify the stage lands in the right spend tier**

`InferenceTier.forStage("rerank")` falls through its `default` branch to `EXTRACTION`. Confirm no change is needed:

Run: `grep -n "case \"synthesizeRecall\"" modules/daemon/src/main/java/dev/alvo/pieria/model/usage/InferenceTier.java`
Expected: the `switch` lists only synthesis/embedding stages, so `"rerank"` maps to `EXTRACTION`. **Do not edit this file.**

- [ ] **Step 7: Run the full suite**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add modules/daemon/src/main/resources/prompts/rerank-candidates.txt \
        modules/daemon/src/main/java/dev/alvo/pieria/model/OpenAiModelGateway.java \
        modules/daemon/src/test/java/dev/alvo/pieria/model/RerankResponseParserTests.java
git commit -m "feat(rerank): implement the small-tier rerank call and its line-protocol parser"
```

---

## Task 5: Configuration plumbing

Six properties from `pieria.properties` through the per-profile override chain (D7). Nothing reads them yet — Task 6 does.

**Files:**
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/config/PieriaProperties.java:314-341`
- Modify: `modules/shared/src/main/java/dev/alvo/pieria/config/model/DaemonOverrides.java`
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/config/EffectiveConfigResolver.java:82-108`
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/config/ProfileConfigService.java:49-71`
- Modify: `modules/daemon/src/main/resources/config/pieria-default-config.toml`
- Modify (12 test files, positional constructors): `modules/shared/src/test/java/dev/alvo/pieria/config/toml/PieriaConfigBindingTests.java`, `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceTestSupport.java`, `modules/daemon/src/test/java/dev/alvo/pieria/config/EffectiveConfigResolverTests.java`, `modules/daemon/src/test/java/dev/alvo/pieria/setup/BootstrapServiceTests.java`, `modules/daemon/src/test/java/dev/alvo/pieria/storage/SqliteMemoryStoreVectorTests.java`, `modules/daemon/src/test/java/dev/alvo/pieria/retrieval/RetrievalServiceTests.java`, `modules/daemon/src/test/java/dev/alvo/pieria/api/ProfileApiTests.java`, `modules/daemon/src/test/java/dev/alvo/pieria/api/ApiContractTests.java`, `modules/daemon/src/test/java/dev/alvo/pieria/api/ProfileConfigDetailTests.java`, `modules/daemon/src/test/java/dev/alvo/pieria/api/ProfileConfigApiTests.java`, `modules/daemon/src/test/java/dev/alvo/pieria/api/StatusControllerTests.java`, `modules/daemon/src/test/java/dev/alvo/pieria/api/ProfileTraceApiTests.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/config/EffectiveConfigResolverTests.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `PieriaProperties.Retrieval.rerankEnabled()`, `.rerankSemanticWeight()`, `.rerankModelEnabled()`, `.rerankWindow()`, `.rerankSnippetChars()`, `.rerankTimeoutMs()` — types `boolean, double, boolean, int, int, long`, appended in that order after `semanticDuplicateThreshold`. `DaemonOverrides.Retrieval` mirrors them with boxed types `Boolean, Double, Boolean, Integer, Integer, Long`.

- [ ] **Step 1: Write the failing test**

Append to `modules/daemon/src/test/java/dev/alvo/pieria/config/EffectiveConfigResolverTests.java`:

```java
  @Test
  void rerankOverridesOverlayFieldByFieldAndNullsInherit() {
    PieriaProperties global = propertiesWith(retrievalWith(true, 0.4, true, 30, 400, 4000L));

    DaemonOverrides overrides = new DaemonOverrides(null, new DaemonOverrides.Retrieval(
      null, null, null, null, null, null, null, null, null, null, null, null, null,
      null, null, null, null, null, null, null, null, null,
      false, null, null, 12, null, null));

    PieriaProperties.Retrieval effective = overlayFor(global, overrides);

    assertThat(effective.rerankEnabled()).isFalse();
    assertThat(effective.rerankWindow()).isEqualTo(12);
    // Untouched fields still inherit the global value rather than resetting to a type default.
    assertThat(effective.rerankSemanticWeight()).isEqualTo(0.4);
    assertThat(effective.rerankModelEnabled()).isTrue();
    assertThat(effective.rerankSnippetChars()).isEqualTo(400);
    assertThat(effective.rerankTimeoutMs()).isEqualTo(4000L);
  }
```

Add these helpers to the same class if it does not already have equivalents (match the existing file's style for building `PieriaProperties`; `overlayFor` should call `EffectiveConfigResolver.withoutOverrides(global)` for the base and exercise the resolver's overlay path the way the file's existing tests do):

```java
  private static PieriaProperties.Retrieval retrievalWith(boolean rerankEnabled, double semanticWeight,
                                                          boolean modelEnabled, int window,
                                                          int snippetChars, long timeoutMs) {
    return new PieriaProperties.Retrieval(true, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000,
      1.0, 1.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60, 0.78,
      rerankEnabled, semanticWeight, modelEnabled, window, snippetChars, timeoutMs);
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.config.EffectiveConfigResolverTests"`
Expected: FAIL — the `Retrieval` constructor does not take 28 arguments.

- [ ] **Step 3: Extend `PieriaProperties.Retrieval`**

In `modules/daemon/src/main/java/dev/alvo/pieria/config/PieriaProperties.java`, append after `semanticDuplicateThreshold` and extend the compact constructor:

```java
                          @DefaultValue("0.78") double semanticDuplicateThreshold,
                          @DefaultValue("true") boolean rerankEnabled,
                          @DefaultValue("0.4") double rerankSemanticWeight,
                          @DefaultValue("true") boolean rerankModelEnabled,
                          @DefaultValue("30") int rerankWindow,
                          @DefaultValue("400") int rerankSnippetChars,
                          @DefaultValue("4000") long rerankTimeoutMs) {

    public Retrieval {
      nearDuplicateThreshold = Math.clamp(nearDuplicateThreshold, 0.0, 1.0);
      semanticDuplicateThreshold = Math.clamp(semanticDuplicateThreshold, 0.0, 1.0);
      rerankSemanticWeight = Math.clamp(rerankSemanticWeight, 0.0, 1.0);
    }
```

- [ ] **Step 4: Extend `DaemonOverrides.Retrieval`**

In `modules/shared/src/main/java/dev/alvo/pieria/config/model/DaemonOverrides.java`, append to the `Retrieval` record:

```java
    Double semanticDuplicateThreshold,
    Boolean rerankEnabled,
    Double rerankSemanticWeight,
    Boolean rerankModelEnabled,
    Integer rerankWindow,
    Integer rerankSnippetChars,
    Long rerankTimeoutMs) {
```

and extend the `isEmpty()` retrieval clause with the six new getters — `ConfigRecordDriftTests` fails until you do, which is the guard working:

```java
      retrieval.recallMode(), retrieval.nearDuplicateThreshold(), retrieval.semanticDuplicateThreshold(),
      retrieval.rerankEnabled(), retrieval.rerankSemanticWeight(), retrieval.rerankModelEnabled(),
      retrieval.rerankWindow(), retrieval.rerankSnippetChars(), retrieval.rerankTimeoutMs()));
```

- [ ] **Step 5: Extend the overlay and the view mapping**

In `EffectiveConfigResolver.overlayRetrieval`, append six lines before the closing paren:

```java
      nvl(o.semanticDuplicateThreshold(), g.semanticDuplicateThreshold()),
      nvl(o.rerankEnabled(), g.rerankEnabled()),
      nvl(o.rerankSemanticWeight(), g.rerankSemanticWeight()),
      nvl(o.rerankModelEnabled(), g.rerankModelEnabled()),
      nvl(o.rerankWindow(), g.rerankWindow()),
      nvl(o.rerankSnippetChars(), g.rerankSnippetChars()),
      nvl(o.rerankTimeoutMs(), g.rerankTimeoutMs()));
```

In `ProfileConfigService.toFullOverrides`, append six getters to the `new Retrieval(...)` call:

```java
        retrieval.semanticDuplicateThreshold(),
        retrieval.rerankEnabled(),
        retrieval.rerankSemanticWeight(),
        retrieval.rerankModelEnabled(),
        retrieval.rerankWindow(),
        retrieval.rerankSnippetChars(),
        retrieval.rerankTimeoutMs()));
```

- [ ] **Step 6: Document the defaults in the TOML**

In `modules/daemon/src/main/resources/config/pieria-default-config.toml`, append under the commented `[pieria.retrieval]` block (after `# semantic-duplicate-threshold = 0.78`, keeping the file's `#`-commented style):

```toml
# rerank-enabled = true
# rerank-semantic-weight = 0.4
# rerank-model-enabled = true
# rerank-window = 30
# rerank-snippet-chars = 400
# rerank-timeout-ms = 4000
```

- [ ] **Step 7: Fix every positional constructor call site**

Run: `grep -rn "new PieriaProperties.Retrieval(\|new Retrieval(\|new DaemonOverrides.Retrieval(" modules --include="*.java" | grep -v "/build/"`

For each **existing test** call site, append `, false, 0.4, true, 30, 400, 4000L` to `PieriaProperties.Retrieval` constructions (or six `null`s to `DaemonOverrides.Retrieval` constructions).

**`rerankEnabled` is `false` in every pre-existing fixture, deliberately.** Those tests assert the pipeline's behaviour without a reranker, and that is exactly the baseline acceptance criterion #1 protects. Task 6 adds new fixtures that turn it on.

- [ ] **Step 8: Run the full suite**

Run: `./gradlew test`
Expected: PASS. Any remaining failure is a call site missed in Step 7 — the compiler names the file and line.

- [ ] **Step 9: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/config/ \
        modules/shared/src/main/java/dev/alvo/pieria/config/model/DaemonOverrides.java \
        modules/daemon/src/main/resources/config/pieria-default-config.toml \
        modules/daemon/src/test modules/shared/src/test
git commit -m "feat(rerank): add the six rerank properties to the retrieval config chain"
```

---

## Task 6: Wire the stage into the pipeline

The load-bearing task (D1, D3, D6). Truncation moves to the end; the embedding read is hoisted; the model call runs bounded on a virtual thread with the usage accumulator re-bound inside it.

**Files:**
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/RetrievalService.java`
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/model/RecallCandidate.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/retrieval/RetrievalServiceTests.java`

**Interfaces:**
- Consumes: `SemanticRescorer`, `ModelReranker`, `RerankInput`, `RerankSettings`, `RerankOutcome` (Tasks 2-3); the six `Retrieval` getters (Task 5); `RecallMode.usesModelAnalysis()`; `InferenceUsageSink.bind(InferenceUsageAccumulator)`.
- Produces: no new public API. `RetrievalDiagnostics.rerank()` is populated for Task 7 to render.

- [ ] **Step 1: Write the failing tests**

Append to `modules/daemon/src/test/java/dev/alvo/pieria/retrieval/RetrievalServiceTests.java`. Add a fixture builder next to the existing `retrievalCfg()`:

```java
  /** As {@link #retrievalCfg()} but with the rerank stage on and the given semantic weight. */
  private static PieriaProperties.Retrieval rerankCfg(double semanticWeight, boolean modelEnabled) {
    return new PieriaProperties.Retrieval(true, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000,
      0.0, 0.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.0, 0.0,
      true, semanticWeight, modelEnabled, 30, 400, 4000L);
  }

  private RetrievalService serviceWithRerank(MemoryStore store, FakeModelGateway model,
                                             PieriaProperties.Retrieval cfg) {
    PieriaProperties props = new PieriaProperties(null, null, null, null,
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS,
        1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70), cfg, null);
    return new RetrievalService(store, model, new DeterministicQueryAnalyzer(), new NoOpCodeIndexStore(),
      EffectiveConfigResolver.withoutOverrides(props), TraceProperties.defaults());
  }
```

and these tests:

```java
  @Test
  void evidenceTierRerankMakesNoModelCall() {
    // The EVIDENCE tier's contract is the query embedding and nothing else. The deterministic
    // re-scorer must run there; the model reranker must not.
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.IRRELEVANT, RerankLabel.ESSENTIAL));
    FakeStore store = storeWithTwoMemories();

    RetrievalService service = serviceWithRerank(store, model, rerankCfg(0.6, true));
    RecallResult result = service.recall("p", "vector search", 10, false, RecallMode.EVIDENCE);

    assertThat(model.rerankCalls).isZero();
    assertThat(result.candidates()).isNotEmpty();
  }

  @Test
  void synthesizedTierConsultsTheModelExactlyOnce() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.RELATED, RerankLabel.ESSENTIAL));
    FakeStore store = storeWithTwoMemories();

    RetrievalService service = serviceWithRerank(store, model, rerankCfg(0.0, true));
    service.recall("p", "vector search", 10, false, RecallMode.SYNTHESIZED);

    assertThat(model.rerankCalls).isEqualTo(1);
  }

  @Test
  void modelLabelsReorderTheReturnedEvidence() {
    FakeModelGateway model = new FakeModelGateway();
    // Label the SECOND fused candidate essential, so it must come back first.
    model.setRerankLabels(List.of(RerankLabel.RELATED, RerankLabel.ESSENTIAL));
    FakeStore store = storeWithTwoMemories();

    RetrievalService service = serviceWithRerank(store, model, rerankCfg(0.0, true));
    RecallResult beforeRerank = serviceWithRerank(store, new FakeModelGateway(), rerankCfg(0.0, false))
      .recall("p", "vector search", 10, false, RecallMode.SYNTHESIZED);
    RecallResult afterRerank = service.recall("p", "vector search", 10, false, RecallMode.SYNTHESIZED);

    assertThat(afterRerank.memories().getFirst().id())
      .isEqualTo(beforeRerank.memories().get(1).id());
  }

  @Test
  void aCandidateRankedBelowTheLimitCanBePromotedIntoTheResult() {
    // The point of moving truncation after the rerank: with limit=1 the second-ranked candidate is
    // unreachable today, and reachable once the model calls it essential.
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.RELATED, RerankLabel.ESSENTIAL));
    FakeStore store = storeWithTwoMemories();

    RecallResult baseline = serviceWithRerank(store, new FakeModelGateway(), rerankCfg(0.0, false))
      .recall("p", "vector search", 1, false, RecallMode.SYNTHESIZED);
    RecallResult promoted = serviceWithRerank(store, model, rerankCfg(0.0, true))
      .recall("p", "vector search", 1, false, RecallMode.SYNTHESIZED);

    assertThat(baseline.memories()).hasSize(1);
    assertThat(promoted.memories()).hasSize(1);
    assertThat(promoted.memories().getFirst().id()).isNotEqualTo(baseline.memories().getFirst().id());
  }

  @Test
  void aFailingRerankModelStillReturnsTheFusedResult() {
    FakeModelGateway model = new FakeModelGateway() {
      @Override
      public List<RerankLabel> rerankCandidates(String query, List<String> contents) {
        throw new IllegalStateException("provider exploded");
      }
    };
    FakeStore store = storeWithTwoMemories();

    RecallResult baseline = serviceWithRerank(store, new FakeModelGateway(), rerankCfg(0.0, false))
      .recall("p", "vector search", 10, false, RecallMode.SYNTHESIZED);
    RecallResult withFailure = serviceWithRerank(store, model, rerankCfg(0.0, true))
      .recall("p", "vector search", 10, false, RecallMode.SYNTHESIZED);

    assertThat(withFailure.memories().stream().map(Memory::id).toList())
      .isEqualTo(baseline.memories().stream().map(Memory::id).toList());
  }

  @Test
  void rerankDiagnosticsAppearOnlyUnderTheDebugFlag() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.ESSENTIAL, RerankLabel.RELATED));
    FakeStore store = storeWithTwoMemories();
    RetrievalService service = serviceWithRerank(store, model, rerankCfg(0.6, true));

    RecallResult quiet = service.recall("p", "vector search", 10, false, RecallMode.SYNTHESIZED);
    RecallResult loud = service.recall("p", "vector search", 10, true, RecallMode.SYNTHESIZED);

    assertThat(quiet.diagnostics()).isNull();
    assertThat(loud.diagnostics().rerank()).extracting(
        dev.alvo.pieria.retrieval.RetrievalDiagnostics.RerankDiagnostics::stage)
      .containsExactly("semantic", "model");
  }

  @Test
  void channelProvenanceSurvivesTheRerankStage() {
    FakeModelGateway model = new FakeModelGateway();
    model.setRerankLabels(List.of(RerankLabel.ESSENTIAL, RerankLabel.RELATED));
    FakeStore store = storeWithTwoMemories();

    RecallResult result = serviceWithRerank(store, model, rerankCfg(0.6, true))
      .recall("p", "vector search", 10, false, RecallMode.SYNTHESIZED);

    assertThat(result.candidates()).allSatisfy(candidate ->
      assertThat(candidate.source()).isNotBlank());
  }
```

and this helper, which seeds the file's existing `FakeStore` (do not add a new store fake — reuse the one already in the file, at `RetrievalServiceTests.java:676`):

```java
  /** Two distinct FTS hits, so fusion produces a two-candidate list the rerank stage can reorder. */
  private static FakeStore storeWithTwoMemories() {
    FakeStore store = new FakeStore();
    store.ftsMemory = List.of(
      mem("m1", "vector search uses sqlite-vec", MemoryType.FACT, "topic.vector", T0),
      mem("m2", "vector search is disabled by default", MemoryType.FACT, "topic.default", T0));
    return store;
  }
```

`rerankCfg` sets both duplicate thresholds to 0, so the collapse pass leaves these two alone and fusion's channel order (`m1`, then `m2`) is what reaches the rerank stage. Add the import `dev.alvo.pieria.retrieval.model.RerankLabel` if not already present.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.retrieval.RetrievalServiceTests"`
Expected: FAIL — no rerank runs, so `rerankCalls` is 0 where 1 is expected and diagnostics are empty.

- [ ] **Step 3: Add the pipeline fields and settings mapping**

In `RetrievalService`, add imports for the rerank package, `RecallMode`, and `InferenceUsageAccumulator`, then add two final fields alongside `temporalExtractor` (both are stateless, so one instance each is right):

```java
  private final SemanticRescorer semanticRescorer = new SemanticRescorer();
  private final ModelReranker modelReranker;
```

and in the constructor, after `this.modelGateway = modelGateway;`:

```java
    this.modelReranker = new ModelReranker(modelGateway);
```

Extend the `Pipeline` record with the settings and build them in `buildPipeline`:

```java
  private record Pipeline(ReciprocalRankFusion fusion,
                          List<RetrievalChannel> channels,
                          List<RetrievalChannel> secondWaveChannels,
                          int channelLimit,
                          long channelTimeoutMs,
                          double nearDuplicateThreshold,
                          double semanticDuplicateThreshold,
                          RerankSettings rerank) {
  }
```

```java
    return new Pipeline(fusion, List.copyOf(wave1), List.copyOf(wave2),
      cfg.channelLimit(), cfg.channelTimeoutMs(), cfg.nearDuplicateThreshold(),
      cfg.semanticDuplicateThreshold(),
      new RerankSettings(cfg.rerankEnabled(), cfg.rerankSemanticWeight(), cfg.rerankModelEnabled(),
        cfg.rerankWindow(), cfg.rerankSnippetChars(), cfg.rerankTimeoutMs()));
```

- [ ] **Step 4: Rewrite `fuse` and the embedding hoist**

Replace `fuse` (currently `RetrievalService.java:413-419`) with:

```java
  /**
   * Fusion stage: weighted RRF, near-duplicate collapse, rerank, then the caller's {@code limit}.
   *
   * <p>The truncation is deliberately last. Applying it before the rerank would make a candidate
   * that RRF ranked below {@code limit} unreachable no matter how relevant it is, which is the
   * promotion the rerank stage exists to perform.
   */
  private List<RecallCandidate> fuse(Pipeline pipeline, List<RetrievalCandidate> hits, int limit,
                                     String profileId, RetrievalContext context, RecallMode mode,
                                     InferenceUsageAccumulator usage,
                                     List<RerankDiagnostics> rerankDiagnostics) {
    List<RecallCandidate> fused = pipeline.fusion().fuse(hits);

    // One store read serves both consumers: the collapse pass's semantic half and the rerank
    // stage's cosine term score the same candidates against the same vectors.
    boolean wantVectors = pipeline.semanticDuplicateThreshold() > 0.0
      || (pipeline.rerank().enabled() && pipeline.rerank().semanticWeight() > 0.0);
    Map<String, float[]> vectors = wantVectors ? embeddingsForCollapse(profileId, fused) : Map.of();

    List<RecallCandidate> distinct = collapseNearDuplicates(fused, pipeline.nearDuplicateThreshold(),
      pipeline.semanticDuplicateThreshold(), vectors);

    List<RecallCandidate> reranked = rerank(pipeline, distinct, vectors, context, mode, usage,
      rerankDiagnostics);

    return reranked.size() > limit ? List.copyOf(reranked.subList(0, limit)) : reranked;
  }

  /**
   * Rerank stage: the deterministic re-scorer in every tier, the model reranker only where the tier
   * already pays for model analysis. Bounded, best-effort, and incapable of failing the recall —
   * every path out of here either improves the order or returns it untouched.
   */
  private List<RecallCandidate> rerank(Pipeline pipeline, List<RecallCandidate> candidates,
                                       Map<String, float[]> vectors, RetrievalContext context,
                                       RecallMode mode, InferenceUsageAccumulator usage,
                                       List<RerankDiagnostics> diagnosticsOut) {
    RerankSettings settings = pipeline.rerank();
    if (!settings.enabled() || candidates.size() < 2) {
      return candidates;
    }

    int windowSize = Math.min(settings.window(), candidates.size());
    List<RecallCandidate> window = List.copyOf(candidates.subList(0, windowSize));
    List<RecallCandidate> tail = List.copyOf(candidates.subList(windowSize, candidates.size()));

    RerankInput input = new RerankInput(context.query(), context.queryEmbedding(), window,
      vectors, settings);

    RerankOutcome rescored = semanticRescorer.rerank(input);
    diagnosticsOut.add(rescored.diagnostics());

    List<RecallCandidate> ordered = rescored.candidates();
    if (settings.modelEnabled() && mode.usesModelAnalysis()) {
      RerankOutcome modelOutcome = runModelRerank(
        new RerankInput(context.query(), context.queryEmbedding(), ordered, vectors, settings),
        settings, usage);
      diagnosticsOut.add(modelOutcome.diagnostics());
      ordered = modelOutcome.candidates();
    }

    if (tail.isEmpty()) {
      return ordered;
    }
    List<RecallCandidate> combined = new ArrayList<>(ordered.size() + tail.size());
    combined.addAll(ordered);
    combined.addAll(tail);
    return List.copyOf(combined);
  }

  /**
   * Run the model reranker on a virtual thread, bounded by the configured timeout — the same
   * best-effort posture {@link #runChannels} gives a non-critical channel.
   *
   * <p>The usage accumulator is re-bound <em>inside</em> the worker because
   * {@link InferenceUsageSink} is thread-bound and virtual threads do not inherit thread-locals.
   * A rerank that times out may still land its tokens after this recall's usage has been recorded;
   * the accumulator is LongAdder-striped so that is safe, it just means a timed-out rerank's tokens
   * can go unbilled for that recall. Losing an accounting line is the right trade against blocking
   * a recall on a slow model.
   */
  private RerankOutcome runModelRerank(RerankInput input, RerankSettings settings,
                                       InferenceUsageAccumulator usage) {
    try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<RerankOutcome> future = exec.submit(() -> {
        try (InferenceUsageSink.Binding binding = InferenceUsageSink.bind(usage)) {
          return modelReranker.rerank(input);
        }
      });
      try {
        return future.get(settings.timeoutMs(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException | ExecutionException | InterruptedException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        future.cancel(true);
        LOGGER.warn("rerank stage failed/timed out ({}); keeping fused order", e.toString());
        return RerankOutcome.passThrough(input.candidates(), "model", settings.timeoutMs());
      }
    }
  }
```

Then change `collapseNearDuplicates` and `embeddingsForCollapse` to take the pre-read map rather than reading it themselves:

```java
  private List<RecallCandidate> collapseNearDuplicates(
    List<RecallCandidate> ranked, double threshold, double semanticThreshold,
    Map<String, float[]> embeddings) {
    if ((threshold <= 0.0 && semanticThreshold <= 0.0) || ranked.size() < 2) {
      return ranked;
    }
    Map<String, float[]> vectors = semanticThreshold <= 0.0 ? Map.of() : embeddings;
```

(the rest of the method body is unchanged, reading `vectors` where it read `embeddings`).

Note that `vectors` is threaded through as a parameter rather than stored on `Pipeline`. `Pipeline` is built per recall but describes *machinery*, not *this recall's data*; putting a per-recall map on it would invite someone to reuse or cache a `Pipeline` later and quietly cross two recalls' vectors.

- [ ] **Step 5: Update the `recall` call site and the latency log**

In `recall(...)`, thread the new arguments through the `fuse` call and record the stage:

```java
      List<RerankDiagnostics> rerankDiagnostics = new ArrayList<>();
      Timed<List<RecallCandidate>> fused = Timed.measure(() -> {
        List<RetrievalCandidate> forFusion = excludeCodeDerived
          ? hits.value().stream().filter(h -> !isCodeDerived(h.memory())).toList()
          : hits.value();
        return fuse(pipeline, forFusion, limit, profile.id(), context, mode, inferenceUsage,
          rerankDiagnostics);
      });
```

Add `rerankMs` to the latency line by summing the stage diagnostics:

```java
      long rerankMs = rerankDiagnostics.stream()
        .mapToLong(RerankDiagnostics::latencyMs).sum();
      LOGGER.info("recall latency profile={} hits={} evidence={} analysisMs={} embeddingMs={} channelsMs={} fusionMs={} rerankMs={} temporalMs={} synthesisMs={} totalMs={}",
        profileName, hits.value().size(), fused.value().size(),
        analysis.millis(), embeddings.millis(), hits.millis(), fused.millis(), rerankMs,
        temporal.millis(), answer.millis(), Timed.elapsedMillis(totalStart));
```

and pass the diagnostics into the result:

```java
      RetrievalDiagnostics diagnostics = debug
        ? new RetrievalDiagnostics(analysis.value(), channelDiagnostics, rerankDiagnostics)
        : null;
```

- [ ] **Step 6: Document the score invariant**

In `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/model/RecallCandidate.java`, replace the javadoc:

```java
/**
 * A retrieval hit handed to synthesis. {@code score} carries the channel/fusion strength.
 * {@code source} records which channel produced it, kept so the multi-channel design slots in
 * without reshaping callers.
 *
 * <p><strong>List order, not {@code score}, is authoritative.</strong> {@code score} is always the
 * RRF score, deliberately: keeping it makes channel provenance interpretable and makes the
 * rerank-disabled path trivially comparable to the enabled one. But the rerank stage reorders
 * candidates without rewriting their scores, so a returned list is generally NOT sorted by
 * {@code score}. Rank candidates by their position; never re-sort them by this field.
 */
public record RecallCandidate(Memory memory, double score, String source) {
}
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.retrieval.RetrievalServiceTests"`
Expected: PASS, including every pre-existing test in the file — they run with `rerankEnabled=false` (Task 5, Step 7) and must be untouched by this change.

- [ ] **Step 8: Run the full suite**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/retrieval/ \
        modules/daemon/src/test/java/dev/alvo/pieria/retrieval/RetrievalServiceTests.java
git commit -m "feat(rerank): run the rerank stage between fusion and the recall limit"
```

---

## Task 7: Surface rerank diagnostics on the API

**Files:**
- Modify: `modules/shared/src/main/java/dev/alvo/pieria/api/response/RecallResponse.java`
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/api/controller/ProfileController.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/api/ProfileApiTests.java`

**Interfaces:**
- Consumes: `RetrievalDiagnostics.RerankDiagnostics` (Task 2).
- Produces: `RecallResponse.RecallDebug.RerankDiagnostic(String stage, int input, int output, int dropped, long latencyMs, boolean fellBack)`; `RecallDebug` gains a fourth component `List<RerankDiagnostic> rerank`.

- [ ] **Step 1: Write the failing test**

`ProfileApiTests` is a `@WebMvcTest` whose `Wiring` class (`ProfileApiTests.java:397`) builds the `PieriaProperties` bean. First turn the stage on there — Task 5 set every pre-existing fixture to `false`, and this is the one class that needs it `true`:

```java
        new PieriaProperties.Retrieval(false, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000,
          0.0, 0.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60, 0.78,
          true, 0.4, true, 30, 400, 4000L),
```

This wiring uses `StubModelGateway`, which does not implement `rerankCandidates`, so the model stage reports no signal and falls back — exactly right for this test, whose job is the wire mapping, not the ranking. The ranking assertions live in Task 6, where the store fake is under the test's control.

Then add these tests next to the existing debug test at `ProfileApiTests.java:263`:

```java
  @Test
  void recallDebugCarriesRerankStageDiagnostics() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/recall")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"query\":\"tea\",\"debug\":true}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.debug.rerank").isArray());
  }

  @Test
  void recallWithoutDebugOmitsTheRerankBlockEntirely() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/recall")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"query\":\"tea\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.debug").doesNotExist());
  }
```

Use the same seeding the neighbouring debug test uses (the query `"tea"` matches what that test already stores).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.api.ProfileApiTests"`
Expected: FAIL — `$.debug.rerank` does not exist.

- [ ] **Step 3: Extend the response record**

In `modules/shared/src/main/java/dev/alvo/pieria/api/response/RecallResponse.java`, extend `RecallDebug`:

```java
  /**
   * Debug payload returned only when {@code debug=true} was requested.
   *
   * @param candidates    fused candidates with RRF score + channel provenance, in rank order
   * @param temporalFacts pre-computed temporal facts injected into synthesis (rendered)
   * @param channels      per-channel latency/hit/failure diagnostics
   * @param rerank        per-rerank-stage diagnostics, in the order the stages ran
   */
  public record RecallDebug(
    List<Provenance> candidates,
    List<String> temporalFacts,
    List<ChannelDiagnostic> channels,
    List<RerankDiagnostic> rerank) {

    /** One fused candidate's provenance: memory id, RRF score, and the channels that produced it. */
    public record Provenance(String id, double score, String source) {
    }

    /** Per-channel diagnostic: which channel, how long it took, how many hits, did it fail. */
    public record ChannelDiagnostic(String channel, long latencyMs, int hits, boolean failed) {
    }

    /**
     * Per-rerank-stage diagnostic. {@code fellBack} is the field to read when a rerank looks like
     * it did nothing: the stage declined to act and returned its input untouched, which is the
     * designed behaviour on every failure, timeout, and absent signal — and invisible from the
     * candidate list alone.
     */
    public record RerankDiagnostic(String stage, int input, int output, int dropped,
                                   long latencyMs, boolean fellBack) {
    }
  }
```

- [ ] **Step 4: Map it in the controller**

In `ProfileController.debugBlock(...)`, add the rerank list to the `RecallDebug` construction:

```java
      result.diagnostics().rerank().stream()
        .map(d -> new RecallDebug.RerankDiagnostic(d.stage(), d.input(), d.output(), d.dropped(),
          d.latencyMs(), d.fellBack()))
        .toList()
```

Add the import for `RecallResponse.RecallDebug.RerankDiagnostic` alongside the existing `ChannelDiagnostic` and `Provenance` imports, and use the short name if the file's style does so.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.api.ProfileApiTests"`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: PASS. `ApiContractTests` may assert the `RecallDebug` shape — update it to include the new component if so.

- [ ] **Step 7: Commit**

```bash
git add modules/shared/src/main/java/dev/alvo/pieria/api/response/RecallResponse.java \
        modules/daemon/src/main/java/dev/alvo/pieria/api/controller/ProfileController.java \
        modules/daemon/src/test/java/dev/alvo/pieria/api/
git commit -m "feat(rerank): surface rerank stage diagnostics under the recall debug flag"
```

---

## Task 8: Console configuration

Twelve schema entries and three small JS edits (spec §6). The schema is the source of truth for both pages, so most of this is a resource edit.

**Files:**
- Modify: `modules/daemon/src/main/resources/config/config-schema.json`
- Modify: `modules/daemon/src/main/resources/static/js/console/config/profile.js`
- Modify: `modules/daemon/src/main/resources/static/js/console/config/global.js`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/config/schema/ConfigSchemaTests.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/api/GlobalConfigApiTests.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/api/ProfileConfigApiTests.java`

**Interfaces:**
- Consumes: the six `DaemonOverrides.Retrieval` and `PieriaProperties.Retrieval` components (Task 5).
- Produces: no Java API. Schema keys `retrieval.rerank-*` (profile) and `pieria.retrieval.rerank-*` (global), section `rerank`.

- [ ] **Step 1: Write the failing test**

Add to `modules/daemon/src/test/java/dev/alvo/pieria/config/schema/ConfigSchemaTests.java`:

```java
  private static final String RETRIEVAL_PREFIX = "pieria.retrieval.";

  // The global scope has no DaemonOverrides to check against, so a mistyped global key would write
  // to pieria.properties and bind to nothing — silently. Only the rerank subset of
  // PieriaProperties.Retrieval is globally editable, so this asserts a subset, not equality.
  @Test
  void globalRetrievalKeysExistOnPieriaPropertiesRetrieval() {
    Set<String> fromCode = kebabComponentNames(dev.alvo.pieria.config.PieriaProperties.Retrieval.class)
      .stream()
      .map(name -> RETRIEVAL_PREFIX + name)
      .collect(Collectors.toCollection(LinkedHashSet::new));

    Set<String> fromSchema = schema.forScope("global").stream()
      .map(ConfigField::key)
      .filter(key -> key.startsWith(RETRIEVAL_PREFIX))
      .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(fromSchema).isNotEmpty().isSubsetOf(fromCode);
  }

  @Test
  void everyRerankKnobIsEditableAtBothScopes() {
    Set<String> suffixes = Set.of("rerank-enabled", "rerank-semantic-weight", "rerank-model-enabled",
      "rerank-window", "rerank-snippet-chars", "rerank-timeout-ms");

    Set<String> profileKeys = schema.forScope("profile").stream()
      .map(ConfigField::key).collect(Collectors.toSet());
    Set<String> globalKeys = schema.forScope("global").stream()
      .map(ConfigField::key).collect(Collectors.toSet());

    suffixes.forEach(suffix -> {
      assertThat(profileKeys).contains("retrieval." + suffix);
      assertThat(globalKeys).contains(RETRIEVAL_PREFIX + suffix);
    });
  }

  @Test
  void rerankFieldsAreGroupedUnderTheirOwnSectionAtBothScopes() {
    assertThat(schema.forScope("profile").stream()
      .filter(f -> f.key().startsWith("retrieval.rerank-"))
      .map(ConfigField::section)).allMatch("rerank"::equals);
    assertThat(schema.forScope("global").stream()
      .filter(f -> f.key().startsWith(RETRIEVAL_PREFIX + "rerank-"))
      .map(ConfigField::section)).allMatch("rerank"::equals);
  }

  // The blend knob must not be named weight-* : channel-mix.js selects fusion channels with
  // key.indexOf("retrieval.weight-") === 0, and a weight-prefixed key would render a non-channel
  // in the channel mix bar and its legend.
  @Test
  void theBlendKnobIsNotNamedLikeAChannelWeight() {
    assertThat(schema.forScope("profile").stream().map(ConfigField::key))
      .noneMatch(key -> key.startsWith("retrieval.weight-") && key.contains("rerank"));
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.config.schema.ConfigSchemaTests"`
Expected: FAIL — `profileScopedKeysMatchDaemonOverridesExactly` fails too, since the record has six components the schema does not list.

- [ ] **Step 3: Add the twelve schema entries**

In `modules/daemon/src/main/resources/config/config-schema.json`, append these six after the `fusion` section's entries:

```json
  {
    "key": "retrieval.rerank-enabled",
    "scope": "profile",
    "section": "rerank",
    "tier": "live",
    "kind": "bool",
    "label": "Reranking enabled",
    "hint": "Off restores the plain fusion ordering."
  },
  {
    "key": "retrieval.rerank-semantic-weight",
    "scope": "profile",
    "section": "rerank",
    "tier": "live",
    "kind": "double",
    "label": "Semantic blend weight",
    "hint": "Share of the score taken from cosine-to-query rather than fusion rank. 0 disables the blend."
  },
  {
    "key": "retrieval.rerank-model-enabled",
    "scope": "profile",
    "section": "rerank",
    "tier": "live",
    "kind": "bool",
    "label": "Model reranking",
    "hint": "Adds one small-model call. Only runs at the analyzed and synthesized tiers."
  },
  {
    "key": "retrieval.rerank-window",
    "scope": "profile",
    "section": "rerank",
    "tier": "live",
    "kind": "int",
    "label": "Candidate window"
  },
  {
    "key": "retrieval.rerank-snippet-chars",
    "scope": "profile",
    "section": "rerank",
    "tier": "live",
    "kind": "int",
    "label": "Candidate text limit (chars)"
  },
  {
    "key": "retrieval.rerank-timeout-ms",
    "scope": "profile",
    "section": "rerank",
    "tier": "live",
    "kind": "int",
    "label": "Rerank timeout (ms)"
  },
```

and these six alongside the other `global` entries (same labels and hints, `scope: "global"`, `tier: "restart"`, keys prefixed `pieria.retrieval.`). `tier` is `restart` because the daemon binds `pieria.properties` once at startup and never re-reads it.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :daemon:test --tests "dev.alvo.pieria.config.schema.ConfigSchemaTests"`
Expected: PASS

- [ ] **Step 5: Add the console section title and the inactive predicate**

In `modules/daemon/src/main/resources/static/js/console/config/profile.js`, add the title:

```js
const SECTION_TITLES = {
  channels: "Retrieval channels",
  graph: "Graph traversal",
  "code-graph": "Code graph",
  fusion: "Fusion and limits",
  rerank: "Reranking",
  ingestion: "Ingestion"
};
```

Replace the single `graphOff` boolean with a per-section predicate. In `render()`, delete the `const graphOff = ...` line and the third argument to `renderSection`:

```js
  bySection(Object.values(schemaFields), "profile").forEach(function (group) {
    root.appendChild(renderSection(group, errors));
  });
```

Add the predicate map next to `SECTION_TITLES`:

```js
// A section whose own switch is off renders read-only: its fields still show their values, but
// editing them would imply an effect they cannot have. Keyed by section so a new one is a line
// here rather than another boolean threaded through renderSection.
const SECTION_INACTIVE_WHEN = {
  graph: function (valueOf) { return Number(valueOf("retrieval.weight-graph")) === 0; },
  rerank: function (valueOf) { return valueOf("retrieval.rerank-enabled") === false; }
};

// The switch that deactivates a section must stay live, or there is no way to switch it back on.
const SECTION_SWITCH = { rerank: "retrieval.rerank-enabled" };
```

and in `renderSection`, replace the `inactive` line and the field row's `disabled` option:

```js
function renderSection(group, errors) {
  const section = el("section", "cfg-section");
  const predicate = SECTION_INACTIVE_WHEN[group.section];
  const inactive = predicate ? predicate(valueOf) : false;
```

```js
      disabled: inactive && field.key !== SECTION_SWITCH[group.section],
```

- [ ] **Step 6: Update the global page's header comment**

In `modules/daemon/src/main/resources/static/js/console/config/global.js`, update the counts in the `groupEntriesBySection` comment:

```js
// Group one tier's entries by section, preserving the order sections first appear. A flat list of
// all 33 restart-tier keys reads as noise; the schema's section already says which of eight
// functional groups (observability, throughput, provider, models, daemon, storage, traces, rerank)
// a key belongs to, so the page should say so too rather than dropping that structure on the floor.
```

- [ ] **Step 7: Write the config API tests**

`GlobalConfigApiTests` calls the controller directly against a `MockEnvironment` and a `@TempDir` config dir — it is **not** a MockMvc test. Follow that style.

First register the running values in `setUp()` alongside the existing `environment.setProperty(...)` lines, so the entries have something to report:

```java
    environment.setProperty("pieria.retrieval.rerank-enabled", "true");
    environment.setProperty("pieria.retrieval.rerank-window", "30");
```

Then add:

```java
  @Test
  void globalRerankKeysAreReportedAsRestartTier() {
    JsonNode entries = controller.get().get("entries");

    JsonNode window = StreamSupport.stream(entries.spliterator(), false)
      .filter(entry -> "pieria.retrieval.rerank-window".equals(entry.get("key").asString()))
      .findFirst()
      .orElseThrow(() -> new AssertionError("rerank-window missing from the global entries"));

    assertThat(window.get("tier").asString()).isEqualTo("restart");
    assertThat(window.get("value").asString()).isEqualTo("30");
  }

  @Test
  void aGlobalRerankWriteLandsInPieriaProperties() throws Exception {
    controller.put(new GlobalConfigController.GlobalConfigUpdate(
      Map.of("pieria.retrieval.rerank-window", "12"), false));

    assertThat(Files.readString(configDir.resolve("pieria.properties")))
      .contains("pieria.retrieval.rerank-window=12");
  }

  @Test
  void aNonNumericRerankWindowIsRejectedAndWritesNothing() {
    assertThatThrownBy(() -> controller.put(new GlobalConfigController.GlobalConfigUpdate(
      Map.of("pieria.retrieval.rerank-window", "wide"), false)))
      .isInstanceOf(IllegalArgumentException.class);
  }
```

Add the imports `java.nio.file.Files`, `java.util.stream.StreamSupport`, and `dev.alvo.pieria.api.controller.GlobalConfigController.GlobalConfigUpdate` if the file does not already have them, and match the file's actual accessor style for `JsonNode` (it uses `tools.jackson.databind.JsonNode`, not the Fasterxml one) by copying how a neighbouring test reads an entry.

`ProfileConfigApiTests` likewise drives the controller directly, against a real SQLite store. Add:

```java
  @Test
  void rerankOverridesRoundTripThroughTheProfileConfigTable() {
    controller.put("p", ConfigCodec.toNode(Map.of(
      "retrieval", Map.of("rerank-enabled", false, "rerank-window", 12))));

    JsonNode effective = controller.get("p");

    assertThat(effective.get("retrieval").get("rerank-enabled").asBoolean()).isFalse();
    assertThat(effective.get("retrieval").get("rerank-window").asInt()).isEqualTo(12);
  }

  @Test
  void aMistypedRerankKeyIsRejectedByTheWhitelist() {
    assertThatThrownBy(() -> controller.put("p", ConfigCodec.toNode(Map.of(
      "retrieval", Map.of("rerank-windows", 12)))))
      .isInstanceOf(IllegalArgumentException.class);
  }
```

Match the file's actual `ProfileConfigController` method signatures by copying a neighbouring test's call — the shapes above assume `put(String, JsonNode)` and `get(String)`, which is what the existing override tests in that file exercise.

- [ ] **Step 8: Run the full suite**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 9: Verify the console renders**

Ask the user to start the daemon and open the console, or if a daemon is already running on the default port, check the schema endpoint directly:

Run: `curl -s localhost:7717/v1/config/schema | python3 -c "import json,sys; print([f['key'] for f in json.load(sys.stdin) if 'rerank' in f['key']])"`
Expected: all 12 keys listed. If no daemon is running, skip this step — the `ConfigSchemaTests` assertions already cover the contract.

- [ ] **Step 10: Commit**

```bash
git add modules/daemon/src/main/resources/config/config-schema.json \
        modules/daemon/src/main/resources/static/js/console/config/ \
        modules/daemon/src/test/java/dev/alvo/pieria/config/schema/ConfigSchemaTests.java \
        modules/daemon/src/test/java/dev/alvo/pieria/api/
git commit -m "feat(console): expose the rerank properties at both config scopes"
```

---

## Task 9: Documentation

**Files:**
- Modify: `docs/POTENTIAL_FEATURES.md`
- Modify: `docs/phases/phase-9-retrieval-reranking-stage.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: Mark the feature shipped**

In `docs/POTENTIAL_FEATURES.md`, change item 3's status line to `Phase: 9 | Status: done` and append a `Shipped:` paragraph in the style of items 1, 2, 6, and 14:

```
Shipped: `retrieval.rerank` (`Reranker`/`SemanticRescorer`/`ModelReranker`/`RerankSettings`) running inside `RetrievalService.fuse()` between near-duplicate collapse and the recall limit. Two sub-stages split by tier: a deterministic cosine-blend re-score in every tier (no model call, reusing the vectors the collapse pass already reads) and a coarse three-way relevance label from the small tier at `ANALYZED`+. Truncation moved after the stage so a candidate ranked below `limit` by RRF can be promoted. Every failure — model down, timeout, misaligned labels, unanimous irrelevance — falls back to fused order. Six `rerank*` properties, per-profile overridable and editable at both console config scopes. The memory-only-vs-mixed rerank flag was dropped: code and memory candidates competing for one context budget is the reason to rerank at all.
```

- [ ] **Step 2: Mark the phase doc complete**

In `docs/phases/phase-9-retrieval-reranking-stage.md`, change the supersession banner's first line from "Superseded in part (2026-09-02)" to "Implemented (<today's date, ISO form>)" — keeping the rest of the banner, which explains what the implementation did differently and why.

- [ ] **Step 3: Add the design constraint to AGENTS.md**

In `AGENTS.md`, under "Key design constraints", add:

```markdown
- **Reranking modulates channel evidence, it does not replace it**: the rerank stage sits between
  near-duplicate collapse and the recall `limit`. The small tier returns a coarse
  `essential`/`related`/`irrelevant` label — never a numeric score — because a local 4-8B model
  clusters numeric relevance judgements too tightly to order anything, and RRF order is preserved
  inside each label bucket so the model supplies the judgement it can make and fusion supplies the
  fine ordering it cannot. `RecallCandidate.score` therefore stays the RRF score and the returned
  list is not sorted by it — **rank by list position, never by `score`**. The stage is incapable of
  failing a recall: model failure, timeout, misaligned labels, and *unanimous* `irrelevant` all
  return the fused order untouched. That last case is a guard, not a verdict — a batch that labels
  everything irrelevant is indistinguishable from a broken one, and honouring it would silently
  blank a recall that worked. The `EVIDENCE` tier runs only the deterministic cosine-blend
  re-scorer, never the model, because its ~1-3s contract is the query embedding and nothing else.
```

- [ ] **Step 4: Run the full suite one last time**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add docs/POTENTIAL_FEATURES.md docs/phases/phase-9-retrieval-reranking-stage.md AGENTS.md
git commit -m "docs(phase-9): mark the reranking stage shipped"
```

---

## Post-implementation: measurement

Not a task — it needs a live model and the user's judgement on the numbers.

The defaults (`rerank-semantic-weight=0.4`, `rerank-window=30`, and the prompt's wording) are provisional in exactly the way the RRF weights are. Measure before trusting them:

```bash
# Baseline: stage off.
./gradlew :eval:locomo --args="--config=/path/to/rerank-off.toml --out=build/eval-off"
# Treatment: stage on.
./gradlew :eval:locomo --args="--config=/path/to/rerank-on.toml --out=build/eval-on"
```

Compare evidence-support rate, answer verdicts (`CORRECT` / `WRONG` / `ABSTAINED` — a rerank that trades `WRONG` for `ABSTAINED` is an improvement, one that trades `CORRECT` for `ABSTAINED` is a regression), and the `rerankMs` figure in the recall latency line. Three sweeps worth running: `rerank-semantic-weight` at 0.2 / 0.4 / 0.6; `rerank-model-enabled` on versus off at a fixed weight; and `rerank-window` at 20 / 30 / 50.
