# Execution-Trace / Tool-Output Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ingest tool calls, their outputs, and their outcomes as first-class memories so a coding agent can recall what command validates a module, why a test failed, and what fixed it.

**Architecture:** A `PostToolUse` hook redacts and spools trace events locally, costing no network round-trip inside the agent's loop. Turn-end hooks drain the spool and POST it to the existing `/ingest` endpoint on a new `traces` field. The daemon derives factual `event` memories deterministically in Java (no model call, keyed so the latest outcome supersedes the previous one), and runs the small model once per batch to derive reusable `instruction` recipes. Both link into the Phase 8 memory graph and, via the existing `payload.symbolIds` key, into the Phase 13 code index.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Gradle Kotlin DSL, Jackson 3 (`tools.jackson`), SQLite + sqlite-vec + FTS5, JUnit 5, AssertJ, picocli (CLI), Spring AI 2.0.0-M6.

**Spec:** `docs/superpowers/specs/2026-08-26-execution-trace-memory-design.md`

## Global Constraints

- **Java 25**, Spring Boot **4.0.6**, Spring AI **2.0.0-M6**. Gradle Kotlin DSL.
- **Jackson 3**: import `tools.jackson.databind.*`, never `com.fasterxml.jackson.databind.*`. Only `com.fasterxml.jackson.core:jackson-annotations` remains on the old coordinates.
- Test classes end in **`*Tests`** (e.g. `RedactionTests`). Assertions via AssertJ `org.assertj.core.api.Assertions.assertThat`.
- **Model gateway dependencies must use fakes/stubs in tests.** CI has no Ollama and no network access.
- **Do not add test seams to production code** — no public/injectable fields or null-fallback overrides that exist only so a test can swap an implementation. For CLI commands, point `--daemon-url` at a throwaway localhost HTTP stub.
- **Cross-module utility code belongs in `shared`** (`dev.alvo.pieria.tools`), never reimplemented per module. See `.claude/rules/utility-code-placement.md`.
- **Never run `nativeCompile`, `nativeDist`, or `deployLocal`.** Verify with `./gradlew test` and `./gradlew compileJava`.
- **`./gradlew test` must pass before every commit.**
- The daemon binds `127.0.0.1` only. Nothing leaves the machine; no telemetry.
- All new daemon code lives under `dev.alvo.pieria`, respecting the module boundaries in `AGENTS.md`.

## Deviations From The Spec

Three corrections found while mapping the spec onto the current code. Each is a deliberate change, not a slip:

1. **Spool location.** The spec says `$PIERIA_HOME/spool/traces/`. `PIERIA_HOME` is the *install root*; `dev.alvo.pieria.tools.os.InstallHome`'s javadoc explicitly says not to conflate it with the app-data root. A spool is runtime state, so it goes under `AppDirs.defaultDataRoot()` — the same concept the database uses.

2. **Config shape.** The spec's `pieria.ingestion.trace.*` prefix implies nesting inside `PieriaProperties.Ingestion`. That record is constructed **positionally in 16 files**, so adding a component churns all of them for no benefit. Instead this is a standalone `@ConfigurationProperties(prefix = "pieria.ingestion.trace")` record, exactly like the existing `AuditProperties`, `ReminiscenceProperties`, `VecProperties`, and `CodeSummarizationProperties`. `@ConfigurationPropertiesScan` on `PieriaApplication` picks it up automatically. **The property prefix the spec specifies is unchanged.**

3. **`pieria.retrieval.trace-boost` → `pieria.ingestion.trace.recall-boost`.** The `Retrieval` record has the same positional-construction problem. Keeping every trace knob in one record is also easier to reason about.

## File Structure

**`shared`** — wire shape and redaction, used by both the CLI hook and the daemon.

| File | Responsibility |
|------|----------------|
| `api/request/TraceStatus.java` (new) | `SUCCESS`/`FAILURE`/`UNKNOWN` with lenient wire parsing |
| `api/request/TraceEventDto.java` (new) | One inbound tool call |
| `api/request/IngestRequest.java` (modify) | Optional `traces`; at least one of messages/traces |
| `tools/Redaction.java` (new) | Truncation, secret scrubbing, path normalization |
| `client/ProfileClient.java` (modify) | `ingestTraces(...)` |

**`daemon`** — the trace pipeline, all under a new `ingestion/trace` package.

| File | Responsibility |
|------|----------------|
| `domain/ContentId.java` (modify) | `forTrace(...)` |
| `config/TraceProperties.java` (new) | All trace tuning |
| `ingestion/trace/CommandSignature.java` (new) | Normalized grouping key + error digest |
| `ingestion/trace/TraceEvent.java` (new) | Redacted domain event with resolved `occurredAt` |
| `ingestion/trace/TraceRelevanceFilter.java` (new) | Deterministic noise rejection |
| `ingestion/trace/TraceMemoryFactory.java` (new) | Deterministic `event` memories |
| `ingestion/trace/TraceGraphBuilder.java` (new) | `GraphFragment` per memory |
| `ingestion/trace/TraceCodeLinker.java` (new) | Code references → `payload.symbolIds` |
| `ingestion/trace/TraceRecipe.java` (new) | One model-derived `(command, statement)` pair |
| `ingestion/trace/TraceRecipeExtractor.java` (new) | Batch recipe derivation + verification |
| `ingestion/trace/TraceIngestionService.java` (new) | Orchestrates the whole path |
| `model/ModelGateway.java` (modify) | `extractTraceRecipes(String)` default method |
| `resources/prompts/extract-trace-recipes.txt` (new) | Prompt text |
| `api/controller/ProfileController.java` (modify) | Route `traces` |

**`cli`** — capture and delivery.

| File | Responsibility |
|------|----------------|
| `modules/hook/TraceSpool.java` (new) | Locked append/drain, growth cap, retention sweep |
| `modules/hook/HookInput.java` (modify) | `PostToolUse` fields |
| `command/hook/CcPostToolUseCommand.java` (new) | Redact, truncate, spool |
| `command/hook/AbstractIngestHookCommand.java` (modify) | Drain policy |
| `modules/harness/ClaudeCodeInstaller.java` (modify) | Register `PostToolUse` |

---

### Task 1: Trace wire shape

**Files:**
- Create: `modules/shared/src/main/java/dev/alvo/pieria/api/request/TraceStatus.java`
- Create: `modules/shared/src/main/java/dev/alvo/pieria/api/request/TraceEventDto.java`
- Modify: `modules/shared/src/main/java/dev/alvo/pieria/api/request/IngestRequest.java`
- Test: `modules/shared/src/test/java/dev/alvo/pieria/api/request/IngestRequestTests.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `TraceStatus.{SUCCESS,FAILURE,UNKNOWN}`, `TraceStatus.fromWire(String)`, `TraceStatus.wire()`; `TraceEventDto(String tool, String args, String output, TraceStatus status, Integer exitCode, String error, Instant startedAt, Instant endedAt)`; `IngestRequest.traces()` returning `List<TraceEventDto>` (never null), and `IngestRequest.hasIngestibleContent()` returning `boolean`.

> The `shared` test classpath has only JUnit and AssertJ — no `jakarta.validation` implementation. So `hasIngestibleContent()` is unit-tested here as a plain method, and the HTTP 400 it produces is covered in Task 11's `@WebMvcTest`.

- [ ] **Step 1: Write the failing test**

Create `modules/shared/src/test/java/dev/alvo/pieria/api/request/IngestRequestTests.java`:

```java
package dev.alvo.pieria.api.request;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestRequestTests {

  private static final IngestRequest.MessageDto MESSAGE =
    new IngestRequest.MessageDto("user", "hello");

  private static final TraceEventDto TRACE = new TraceEventDto(
    "Bash", "./gradlew test", "BUILD FAILED", TraceStatus.FAILURE, 1, null, null, null);

  @Test
  void messagesOnlyRequestIsIngestible() {
    assertThat(new IngestRequest("s1", List.of(MESSAGE)).hasIngestibleContent()).isTrue();
  }

  @Test
  void tracesOnlyRequestIsIngestible() {
    IngestRequest request = new IngestRequest("s1", null, null, null, List.of(TRACE));

    assertThat(request.hasIngestibleContent()).isTrue();
    assertThat(request.messages()).isEmpty();
  }

  @Test
  void mixedRequestIsIngestible() {
    assertThat(new IngestRequest("s1", List.of(MESSAGE), null, null, List.of(TRACE))
      .hasIngestibleContent()).isTrue();
  }

  @Test
  void emptyRequestIsNotIngestible() {
    assertThat(new IngestRequest("s1", List.of()).hasIngestibleContent()).isFalse();
    assertThat(new IngestRequest("s1", null, null, null, null).hasIngestibleContent()).isFalse();
  }

  // Null lists are normalized so callers never branch on null; the legacy two- and three-arg
  // constructors must keep working unchanged for every existing caller.
  @Test
  void listsAreNeverNull() {
    IngestRequest request = new IngestRequest("s1", null, null, null, null);

    assertThat(request.messages()).isEmpty();
    assertThat(request.traces()).isEmpty();
  }

  @Test
  void traceStatusParsesLeniently() {
    assertThat(TraceStatus.fromWire("  Failure ")).isEqualTo(TraceStatus.FAILURE);
    assertThat(TraceStatus.fromWire(null)).isEqualTo(TraceStatus.UNKNOWN);
    assertThat(TraceStatus.fromWire("nonsense")).isEqualTo(TraceStatus.UNKNOWN);
    assertThat(TraceStatus.SUCCESS.wire()).isEqualTo("success");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:test --tests 'dev.alvo.pieria.api.request.IngestRequestTests'`
Expected: FAIL — compilation error, `TraceStatus` and `TraceEventDto` do not exist.

- [ ] **Step 3: Write TraceStatus**

Create `modules/shared/src/main/java/dev/alvo/pieria/api/request/TraceStatus.java`:

```java
package dev.alvo.pieria.api.request;

import java.util.Locale;

/**
 * How a tool call ended. Stored lower-cased in trace payloads, mirroring
 * {@code MemoryType}'s wire convention.
 *
 * <p>Parsing is deliberately lenient where {@code MemoryType.fromWire} throws: a harness that
 * reports a status Pieria does not recognize should degrade to {@link #UNKNOWN}, not fail the
 * whole ingest. An unrecognized status still carries the command, which is most of the signal.
 */
public enum TraceStatus {
  SUCCESS,
  FAILURE,
  UNKNOWN;

  /** Parse a wire value case-insensitively; unknown, blank, and null all yield {@link #UNKNOWN}. */
  public static TraceStatus fromWire(String value) {
    if (value == null || value.isBlank()) {
      return UNKNOWN;
    }
    try {
      return TraceStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return UNKNOWN;
    }
  }

  /** Canonical wire/storage form, e.g. {@code "failure"}. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
```

- [ ] **Step 4: Write TraceEventDto**

Create `modules/shared/src/main/java/dev/alvo/pieria/api/request/TraceEventDto.java`:

```java
package dev.alvo.pieria.api.request;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * One inbound tool call: what ran, what it produced, and how it ended.
 *
 * <p>Both timestamps are optional because not every harness reports them. The daemon resolves the
 * event time as {@code endedAt} → {@code startedAt} → its own receipt clock, and counts the
 * receipt-clock case, so a trace with no timestamps is never silently indistinguishable from one
 * that genuinely ran at ingest time.
 *
 * <p>{@code output} and {@code error} are expected to arrive already redacted and capped by the
 * capturing hook; the daemon redacts again on receipt so a direct API caller is covered too.
 */
public record TraceEventDto(
  @NotBlank String tool,
  String args,
  String output,
  TraceStatus status,
  Integer exitCode,
  String error,
  Instant startedAt,
  Instant endedAt) {

  public TraceEventDto {
    status = status == null ? TraceStatus.UNKNOWN : status;
  }
}
```

- [ ] **Step 5: Extend IngestRequest**

In `modules/shared/src/main/java/dev/alvo/pieria/api/request/IngestRequest.java`, replace the record declaration and its two convenience constructors. Keep the existing class javadoc, appending the `traces` paragraph.

Add these imports: `jakarta.validation.constraints.AssertTrue`, `java.util.List` (already present).
**Remove** the `jakarta.validation.constraints.NotEmpty` import — nothing else uses it.

```java
public record IngestRequest(
  @NotBlank String sessionId,
  @Valid List<MessageDto> messages,
  @Positive Integer extractionSamples,
  Instant occurredAt,
  @Valid List<TraceEventDto> traces) {

  public IngestRequest {
    messages = messages == null ? List.of() : List.copyOf(messages);
    traces = traces == null ? List.of() : List.copyOf(traces);
  }

  /** Convenience for callers that don't override sampling or supply an occurrence time. */
  public IngestRequest(String sessionId, List<MessageDto> messages) {
    this(sessionId, messages, null, null, null);
  }

  /** Convenience for callers that override sampling but not the occurrence time. */
  public IngestRequest(String sessionId, List<MessageDto> messages, Integer extractionSamples) {
    this(sessionId, messages, extractionSamples, null, null);
  }

  /** Convenience for the four-argument shape that predates trace ingest. */
  public IngestRequest(String sessionId, List<MessageDto> messages, Integer extractionSamples,
                       Instant occurredAt) {
    this(sessionId, messages, extractionSamples, occurredAt, null);
  }

  /**
   * At least one of {@code messages} / {@code traces} must carry something. Replaces the
   * {@code @NotEmpty} that used to sit on {@code messages}: a traces-only ingest is now valid, but
   * an ingest carrying neither is still a 400 rather than a silent no-op.
   */
  @AssertTrue(message = "at least one of 'messages' or 'traces' must be non-empty")
  public boolean hasIngestibleContent() {
    return !messages.isEmpty() || !traces.isEmpty();
  }
```

Append the following paragraph to the record's class javadoc:

```java
 * <p>{@code traces} carries structured tool calls — commands run, edits made, tests executed —
 * shipped by a harness {@code PostToolUse} hook rather than typed by anyone. It is independent of
 * {@code messages}: a request may carry either, or both. Trace ingest runs its own deterministic
 * path and does not go through chunked extraction.
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :shared:test --tests 'dev.alvo.pieria.api.request.IngestRequestTests'`
Expected: PASS

- [ ] **Step 7: Verify no caller broke**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL. The three legacy constructors are preserved, so every existing `new IngestRequest(...)` still resolves.

- [ ] **Step 8: Commit**

```bash
git add modules/shared/src/main/java/dev/alvo/pieria/api/request/ \
        modules/shared/src/test/java/dev/alvo/pieria/api/request/
git commit -m "feat(trace): add TraceEventDto wire shape and optional traces on IngestRequest"
```

---

### Task 2: Redaction utility

**Files:**
- Create: `modules/shared/src/main/java/dev/alvo/pieria/tools/Redaction.java`
- Test: `modules/shared/src/test/java/dev/alvo/pieria/tools/RedactionTests.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Redaction.truncate(String text, int budget)` → `String`; `Redaction.redactSecrets(String text)` → `Redaction.Redacted(String text, int hits)`; `Redaction.normalizePaths(String text, Path repoRoot, Path userHome)` → `String`; `Redaction.scrub(String text, int budget, Path repoRoot, Path userHome)` → `Redacted` (the composed pipeline: truncate, then redact, then normalize paths).

> `scrub` truncates **first**. This is not an optimization detail — it is what bounds the hook's cost by the budget instead of by raw output size, and `PostToolUse` runs inside the agent's loop after every tool call.

- [ ] **Step 1: Write the failing test**

Create `modules/shared/src/test/java/dev/alvo/pieria/tools/RedactionTests.java`:

```java
package dev.alvo.pieria.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RedactionTests {

  @Test
  void shortTextIsNotTruncated() {
    assertThat(Redaction.truncate("hello", 100)).isEqualTo("hello");
    assertThat(Redaction.truncate(null, 100)).isNull();
  }

  // The trailing lines carry the error, which is the whole reason a failing trace is worth
  // storing. Head-only truncation would clip exactly the signal.
  @Test
  void truncationKeepsBothEndsAndFavoursTheTail() {
    String text = "HEAD" + "x".repeat(500) + "TAIL";

    String truncated = Redaction.truncate(text, 100);

    assertThat(truncated).startsWith("HEAD");
    assertThat(truncated).endsWith("TAIL");
    assertThat(truncated).contains("elided");
    assertThat(truncated.length()).isLessThan(text.length());
  }

  @Test
  void assignmentStyleSecretsAreRedacted() {
    Redaction.Redacted result = Redaction.redactSecrets(
      "export API_KEY=abcd1234efgh5678\npassword: hunter2trombone");

    assertThat(result.text()).doesNotContain("abcd1234efgh5678");
    assertThat(result.text()).doesNotContain("hunter2trombone");
    assertThat(result.text()).contains("[redacted]");
    assertThat(result.hits()).isEqualTo(2);
  }

  @Test
  void wellKnownTokenShapesAreRedacted() {
    String text = String.join("\n",
      "ghp_" + "a".repeat(36),
      "sk-" + "b".repeat(32),
      "Bearer " + "c".repeat(24),
      "AKIA" + "D".repeat(16));

    Redaction.Redacted result = Redaction.redactSecrets(text);

    assertThat(result.text()).doesNotContain("a".repeat(36));
    assertThat(result.text()).doesNotContain("b".repeat(32));
    assertThat(result.text()).doesNotContain("c".repeat(24));
    assertThat(result.text()).doesNotContain("AKIA" + "D".repeat(16));
    assertThat(result.hits()).isEqualTo(4);
  }

  @Test
  void privateKeyBlockIsRedacted() {
    String text = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\nlines\n-----END RSA PRIVATE KEY-----";

    Redaction.Redacted result = Redaction.redactSecrets(text);

    assertThat(result.text()).doesNotContain("MIIEow");
    assertThat(result.hits()).isEqualTo(1);
  }

  @Test
  void cleanTextIsUnchangedAndScoresNoHits() {
    Redaction.Redacted result = Redaction.redactSecrets("./gradlew test\nBUILD FAILED");

    assertThat(result.text()).isEqualTo("./gradlew test\nBUILD FAILED");
    assertThat(result.hits()).isZero();
  }

  @Test
  void repoAndHomePathsAreNormalized() {
    Path repo = Path.of("/Users/dev/projects/pieria");
    Path home = Path.of("/Users/dev");

    String text = Redaction.normalizePaths(
      "error in /Users/dev/projects/pieria/modules/daemon/App.java and /Users/dev/.m2/settings.xml",
      repo, home);

    assertThat(text).contains("./modules/daemon/App.java");
    assertThat(text).contains("~/.m2/settings.xml");
    assertThat(text).doesNotContain("/Users/dev");
  }

  // Redaction runs in the hook and again in the daemon; running it twice must not corrupt the text
  // or double-count.
  @Test
  void scrubIsIdempotent() {
    Path repo = Path.of("/repo");
    String raw = "cd /repo/src && TOKEN=zzzzzzzzzzzzzzzz ./run.sh";

    Redaction.Redacted once = Redaction.scrub(raw, 4000, repo, Path.of("/home/dev"));
    Redaction.Redacted twice = Redaction.scrub(once.text(), 4000, repo, Path.of("/home/dev"));

    assertThat(twice.text()).isEqualTo(once.text());
    assertThat(twice.hits()).isZero();
  }

  @Test
  void scrubTruncatesBeforeRedacting() {
    // A secret past the budget never reaches the regex, and never reaches disk.
    String raw = "start" + "-".repeat(9000) + "API_KEY=abcd1234efgh5678";

    Redaction.Redacted result = Redaction.scrub(raw, 200, Path.of("/repo"), Path.of("/home"));

    assertThat(result.text().length()).isLessThanOrEqualTo(260);
    assertThat(result.text()).doesNotContain("abcd1234efgh5678");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:test --tests 'dev.alvo.pieria.tools.RedactionTests'`
Expected: FAIL — compilation error, `Redaction` does not exist.

- [ ] **Step 3: Write Redaction**

Create `modules/shared/src/main/java/dev/alvo/pieria/tools/Redaction.java`:

```java
package dev.alvo.pieria.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounds and scrubs untrusted tool output before it is stored, embedded, or sent to a model.
 *
 * <p>Lives in {@code shared} because two modules apply it: the CLI hook scrubs before anything
 * reaches the spool file, and the daemon scrubs again on receipt so a direct API caller gets the
 * same treatment. Running it twice is safe — {@link #scrub} is idempotent, and the second pass
 * reports zero hits.
 *
 * <p>Redaction is best-effort pattern matching, not a guarantee. The patterns below cover the
 * shapes that actually show up in build logs and shell history; they must be maintained.
 */
public final class Redaction {

  /** Fraction of the truncation budget given to the head; the rest goes to the tail. */
  private static final double HEAD_SHARE = 0.4;

  private static final String MASK = "[redacted]";

  /**
   * Secret shapes, each with exactly one capturing group holding the value to mask. Ordered
   * most-specific first so a PEM block is not partially eaten by a looser rule.
   */
  private static final List<Pattern> SECRETS = List.of(
    // PEM private key blocks, including the body across lines.
    Pattern.compile("(-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----)"),
    // Provider-issued tokens with recognizable prefixes.
    Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{16,})"),
    Pattern.compile("\\b(sk-[A-Za-z0-9_-]{16,})"),
    Pattern.compile("\\b(xox[abprs]-[A-Za-z0-9-]{10,})"),
    Pattern.compile("\\b(AKIA[0-9A-Z]{16})\\b"),
    // Authorization headers.
    Pattern.compile("(?i)\\bBearer\\s+([A-Za-z0-9._~+/=-]{12,})"),
    // key=value / key: value assignments whose key names a credential.
    Pattern.compile("(?i)\\b(?:api[_-]?key|secret|token|password|passwd|pwd|auth|credential)\\b"
      + "\\s*[:=]\\s*[\"']?([^\\s\"']{6,})[\"']?"));

  private Redaction() {
  }

  /**
   * Scrubbed text and how many secret matches were masked. The count exists so redaction activity
   * can be logged without logging any of the redacted content.
   */
  public record Redacted(String text, int hits) {
  }

  /**
   * Cap {@code text} at roughly {@code budget} characters, keeping the head and — with the larger
   * share — the tail, joined by an elision marker. The tail wins because a build log's last lines
   * are the failure; truncating head-only would discard exactly what makes the trace worth storing.
   */
  public static String truncate(String text, int budget) {
    if (text == null || budget <= 0 || text.length() <= budget) {
      return text;
    }
    int head = (int) (budget * HEAD_SHARE);
    int tail = budget - head;
    int elided = text.length() - head - tail;
    return text.substring(0, head)
      + "\n…[" + elided + " chars elided]…\n"
      + text.substring(text.length() - tail);
  }

  /** Mask every recognized secret in {@code text}, reporting how many were masked. */
  public static Redacted redactSecrets(String text) {
    if (text == null || text.isEmpty()) {
      return new Redacted(text, 0);
    }
    String current = text;
    int hits = 0;
    for (Pattern pattern : SECRETS) {
      Matcher matcher = pattern.matcher(current);
      StringBuilder out = new StringBuilder(current.length());
      while (matcher.find()) {
        hits++;
        // Replace only the captured value, preserving the surrounding key/prefix so the line still
        // reads as "API_KEY=[redacted]" rather than vanishing entirely.
        String whole = matcher.group();
        String value = matcher.group(1);
        String masked = whole.substring(0, whole.lastIndexOf(value)) + MASK;
        matcher.appendReplacement(out, Matcher.quoteReplacement(masked));
      }
      matcher.appendTail(out);
      current = out.toString();
    }
    return new Redacted(current, hits);
  }

  /**
   * Rewrite machine-specific absolute paths: {@code repoRoot} becomes {@code ./}, and the user's
   * home becomes {@code ~}. Longest prefix first, so a repo inside the home directory does not get
   * the weaker rewrite. Null roots are skipped.
   */
  public static String normalizePaths(String text, Path repoRoot, Path userHome) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String current = text;
    if (repoRoot != null) {
      current = current.replace(repoRoot.toAbsolutePath() + "/", "./");
      current = current.replace(repoRoot.toAbsolutePath().toString(), ".");
    }
    if (userHome != null) {
      current = current.replace(userHome.toAbsolutePath() + "/", "~/");
      current = current.replace(userHome.toAbsolutePath().toString(), "~");
    }
    return current;
  }

  /**
   * The full pipeline in the order that matters: truncate, then redact, then normalize paths.
   *
   * <p>Truncating first is deliberate. It bounds the regex work by the budget rather than by the
   * raw output size, which matters because this runs inside a {@code PostToolUse} hook on the
   * agent's critical path. A secret beyond the budget is discarded rather than scanned — it never
   * reaches disk either way.
   */
  public static Redacted scrub(String text, int budget, Path repoRoot, Path userHome) {
    Redacted redacted = redactSecrets(truncate(text, budget));
    return new Redacted(normalizePaths(redacted.text(), repoRoot, userHome), redacted.hits());
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:test --tests 'dev.alvo.pieria.tools.RedactionTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/shared/src/main/java/dev/alvo/pieria/tools/Redaction.java \
        modules/shared/src/test/java/dev/alvo/pieria/tools/RedactionTests.java
git commit -m "feat(trace): add shared Redaction utility for bounding and scrubbing tool output"
```

---

### Task 3: Command signature and error digest

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/CommandSignature.java`
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/domain/ContentId.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/CommandSignatureTests.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/domain/ContentIdTraceTests.java`

**Interfaces:**
- Consumes: `Hash.hash128(String...)`, `StringKit.nullToEmpty(String)` from Task 2's module (`shared`, already present).
- Produces: `CommandSignature.of(String tool, String args)` → `String` slug; `CommandSignature.errorDigest(String errorOrOutput)` → `String`; `ContentId.forTrace(String profileId, String sessionId, String tool, String canonicalArgs, TraceStatus status, Instant occurredAt)` → `String`.

> `forTrace` hashes the **full redacted args**, not the signature. The signature strips flags, so using it here would collapse `./gradlew test` and `./gradlew test --rerun-tasks` into one id and silently drop the second. Signature groups for supersession; the id distinguishes.

- [ ] **Step 1: Write the failing test for CommandSignature**

Create `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/CommandSignatureTests.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandSignatureTests {

  @Test
  void bashCommandDropsLeadingDotSlashAndFlags() {
    assertThat(CommandSignature.of("Bash", "./gradlew test --info")).isEqualTo("gradlew-test");
    assertThat(CommandSignature.of("Bash", "gradlew test")).isEqualTo("gradlew-test");
  }

  @Test
  void moduleQualifiedGradleTaskKeepsItsModule() {
    assertThat(CommandSignature.of("Bash", "./gradlew :daemon:test"))
      .isEqualTo("gradlew-daemon-test");
  }

  // Flag values and bare numbers are run-specific noise; two runs of the same command with
  // different tuning must land on one key so the newer outcome supersedes the older.
  @Test
  void flagValuesAndNumbersAreDropped() {
    assertThat(CommandSignature.of("Bash", "npm test -- --workers 4"))
      .isEqualTo(CommandSignature.of("Bash", "npm test"));
  }

  @Test
  void tokenCountIsCappedAtFour() {
    assertThat(CommandSignature.of("Bash", "a b c d e f g")).isEqualTo("a-b-c-d");
  }

  // Bash is the only tool whose args already name the program. Every other tool needs its own
  // name in the key, or an Edit and a Write of the same file would collide.
  @Test
  void nonBashToolsArePrefixedWithTheToolName() {
    assertThat(CommandSignature.of("Edit", "src/main/java/Foo.java"))
      .isEqualTo("edit-src-main-java-foo-java");
    assertThat(CommandSignature.of("Write", "src/main/java/Foo.java"))
      .isEqualTo("write-src-main-java-foo-java");
  }

  @Test
  void blankArgsFallBackToTheToolName() {
    assertThat(CommandSignature.of("Bash", "  ")).isEqualTo("bash");
    assertThat(CommandSignature.of("Bash", null)).isEqualTo("bash");
  }

  @Test
  void emptyErrorDigestsToASentinel() {
    assertThat(CommandSignature.errorDigest(null)).isEqualTo("none");
    assertThat(CommandSignature.errorDigest("   ")).isEqualTo("none");
  }

  // A recompile shifts stack frames by a line without changing what failed. Masking line and
  // column numbers is what stops that from reading as a new outcome and churning supersession.
  @Test
  void lineAndColumnNumbersDoNotChangeTheDigest() {
    String first = "at dev.alvo.Foo.bar(Foo.java:52)";
    String second = "at dev.alvo.Foo.bar(Foo.java:71)";

    assertThat(CommandSignature.errorDigest(first))
      .isEqualTo(CommandSignature.errorDigest(second));
  }

  @Test
  void differentFailuresDigestDifferently() {
    assertThat(CommandSignature.errorDigest("NullPointerException in Foo"))
      .isNotEqualTo(CommandSignature.errorDigest("AssertionError in Bar"));
  }

  @Test
  void onlyTheTailIsDigested() {
    String shared = "x".repeat(600) + "SAME TAIL";

    assertThat(CommandSignature.errorDigest("PREFIX-A" + shared))
      .isEqualTo(CommandSignature.errorDigest("PREFIX-B" + shared));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.CommandSignatureTests'`
Expected: FAIL — compilation error, `CommandSignature` does not exist.

- [ ] **Step 3: Write CommandSignature**

Create `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/CommandSignature.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.tools.Hash;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The normalized grouping key behind both trace topic keys: {@code trace:outcome:<signature>} and
 * {@code trace:recipe:<signature>}.
 *
 * <p>Its job is <em>grouping</em>, so that a second run of the same command supersedes the first.
 * That is the opposite of an identifier's job, which is why {@code ContentId.forTrace} hashes the
 * full redacted args instead: collapsing {@code ./gradlew test} and
 * {@code ./gradlew test --rerun-tasks} into one signature is correct, and collapsing them into one
 * id would silently drop the second trace.
 */
public final class CommandSignature {

  /** How many meaningful tokens survive into the key. */
  private static final int MAX_TOKENS = 4;

  /** How much of the error tail feeds the digest. */
  private static final int DIGEST_TAIL_CHARS = 512;

  private static final Pattern NUMERIC = Pattern.compile("\\d+");
  private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
  private static final Pattern LINE_COLUMN = Pattern.compile(":\\d+(?::\\d+)?\\b");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private CommandSignature() {
  }

  /**
   * Normalized key for a tool invocation. {@code Bash} args already name the program, so they stand
   * alone; every other tool is prefixed with its own name, or an {@code Edit} and a {@code Write}
   * of the same file would share a key.
   */
  public static String of(String tool, String args) {
    String toolSlug = slug(tool == null ? "" : tool);
    List<String> tokens = meaningfulTokens(args);
    if (tokens.isEmpty()) {
      return toolSlug.isEmpty() ? "unknown" : toolSlug;
    }
    String body = String.join("-", tokens);
    return "bash".equals(toolSlug) ? body : (toolSlug.isEmpty() ? body : toolSlug + "-" + body);
  }

  /**
   * A stable fingerprint of how a command failed, used to tell "the same failure again" from "a
   * different failure". Only the tail is digested (that is where the error lands), line and column
   * numbers are masked (a recompile shifts them without changing what broke), and an empty error
   * yields a fixed sentinel so success-to-success comparison rests on status alone.
   */
  public static String errorDigest(String errorOrOutput) {
    if (errorOrOutput == null || errorOrOutput.isBlank()) {
      return "none";
    }
    String text = errorOrOutput.strip();
    if (text.length() > DIGEST_TAIL_CHARS) {
      text = text.substring(text.length() - DIGEST_TAIL_CHARS);
    }
    String masked = LINE_COLUMN.matcher(text).replaceAll(":#");
    String collapsed = WHITESPACE.matcher(masked).replaceAll(" ").strip();
    return Hash.hash128(collapsed.toLowerCase(Locale.ROOT));
  }

  /**
   * Lowercase args, drop the leading {@code ./}, discard flags and their values and bare numbers,
   * then keep the first {@value #MAX_TOKENS} tokens.
   */
  private static List<String> meaningfulTokens(String args) {
    if (args == null || args.isBlank()) {
      return List.of();
    }
    String[] raw = WHITESPACE.split(args.strip().toLowerCase(Locale.ROOT));
    List<String> tokens = new ArrayList<>();
    boolean skipNextAsFlagValue = false;
    for (String token : raw) {
      if (token.isEmpty()) {
        continue;
      }
      if (token.startsWith("-")) {
        // A flag; its following token is presumed to be its value unless the flag is `--key=value`.
        skipNextAsFlagValue = !token.contains("=");
        continue;
      }
      if (skipNextAsFlagValue) {
        skipNextAsFlagValue = false;
        continue;
      }
      if (NUMERIC.matcher(token).matches()) {
        continue;
      }
      String cleaned = slug(stripLeadingDotSlash(token));
      if (cleaned.isEmpty()) {
        continue;
      }
      tokens.add(cleaned);
      if (tokens.size() == MAX_TOKENS) {
        break;
      }
    }
    return tokens;
  }

  private static String stripLeadingDotSlash(String token) {
    return token.startsWith("./") ? token.substring(2) : token;
  }

  /** Lowercase, replace every run of non-alphanumerics with a single dash, trim dashes. */
  private static String slug(String raw) {
    String lower = raw.toLowerCase(Locale.ROOT);
    String dashed = NON_SLUG.matcher(lower).replaceAll("-");
    int start = 0;
    int end = dashed.length();
    while (start < end && dashed.charAt(start) == '-') {
      start++;
    }
    while (end > start && dashed.charAt(end - 1) == '-') {
      end--;
    }
    return dashed.substring(start, end);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.CommandSignatureTests'`
Expected: PASS

- [ ] **Step 5: Write the failing test for ContentId.forTrace**

Create `modules/daemon/src/test/java/dev/alvo/pieria/domain/ContentIdTraceTests.java`:

```java
package dev.alvo.pieria.domain;

import dev.alvo.pieria.api.request.TraceStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ContentIdTraceTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  @Test
  void idIsStableAcrossCalls() {
    String first = ContentId.forTrace("p1", "s1", "Bash", "./gradlew test", TraceStatus.FAILURE, AT);
    String second = ContentId.forTrace("p1", "s1", "Bash", "./gradlew test", TraceStatus.FAILURE, AT);

    assertThat(first).isEqualTo(second);
  }

  @Test
  void idIs32HexCharacters() {
    String id = ContentId.forTrace("p1", "s1", "Bash", "./gradlew test", TraceStatus.FAILURE, AT);

    assertThat(id).hasSize(32).matches("[0-9a-f]{32}");
  }

  // Profile scoping mirrors messages and memories: identical traces coexist across profiles,
  // re-ingest within one profile is a no-op.
  @Test
  void differentProfilesGetDifferentIds() {
    assertThat(ContentId.forTrace("p1", "s1", "Bash", "x", TraceStatus.SUCCESS, AT))
      .isNotEqualTo(ContentId.forTrace("p2", "s1", "Bash", "x", TraceStatus.SUCCESS, AT));
  }

  // The regression this pins: hashing the CommandSignature instead of the full args would
  // collapse these two and silently drop the second trace.
  @Test
  void argsThatDifferOnlyByFlagsGetDifferentIds() {
    assertThat(ContentId.forTrace("p1", "s1", "Bash", "./gradlew test", TraceStatus.SUCCESS, AT))
      .isNotEqualTo(
        ContentId.forTrace("p1", "s1", "Bash", "./gradlew test --rerun-tasks", TraceStatus.SUCCESS, AT));
  }

  @Test
  void statusAndTimeParticipateInTheId() {
    String base = ContentId.forTrace("p1", "s1", "Bash", "x", TraceStatus.SUCCESS, AT);

    assertThat(base).isNotEqualTo(ContentId.forTrace("p1", "s1", "Bash", "x", TraceStatus.FAILURE, AT));
    assertThat(base).isNotEqualTo(
      ContentId.forTrace("p1", "s1", "Bash", "x", TraceStatus.SUCCESS, AT.plusSeconds(1)));
  }

  @Test
  void nullFieldsAreTolerated() {
    assertThat(ContentId.forTrace(null, null, "Bash", null, TraceStatus.UNKNOWN, null))
      .hasSize(32);
  }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.domain.ContentIdTraceTests'`
Expected: FAIL — compilation error, `ContentId.forTrace` does not exist.

- [ ] **Step 7: Add forTrace to ContentId**

In `modules/daemon/src/main/java/dev/alvo/pieria/domain/ContentId.java`, add these imports:

```java
import dev.alvo.pieria.api.request.TraceStatus;
import java.time.Instant;
```

Append this method inside the class, after `forMemory` and before `forEntity`:

```java
  /**
   * Id for a raw trace event: hashes profile, session, tool, canonical args, status, and the
   * resolved event time. Re-shipping the same trace within a profile collapses to one row, while
   * identical traces in different profiles stay distinct — the same rule messages and memories use.
   *
   * <p>{@code canonicalArgs} is the <em>redacted, path-normalized, full</em> argument string,
   * deliberately not the {@code CommandSignature}. The signature strips flags so that repeated runs
   * of one command share a topic key; using it here would make {@code ./gradlew test} and
   * {@code ./gradlew test --rerun-tasks} the same trace and drop the second.
   */
  public static String forTrace(String profileId,
                                String sessionId,
                                String tool,
                                String canonicalArgs,
                                TraceStatus status,
                                Instant occurredAt) {
    return hash128(
      nullToEmpty(profileId),
      nullToEmpty(sessionId),
      nullToEmpty(tool),
      nullToEmpty(canonicalArgs),
      status == null ? "" : status.wire(),
      occurredAt == null ? "" : occurredAt.toString());
  }
```

- [ ] **Step 8: Run test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.domain.ContentIdTraceTests'`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/CommandSignature.java \
        modules/daemon/src/main/java/dev/alvo/pieria/domain/ContentId.java \
        modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/CommandSignatureTests.java \
        modules/daemon/src/test/java/dev/alvo/pieria/domain/ContentIdTraceTests.java
git commit -m "feat(trace): add CommandSignature grouping key and ContentId.forTrace"
```

---

### Task 4: Trace configuration and domain event

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/config/TraceProperties.java`
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceEvent.java`
- Modify: `modules/daemon/src/main/resources/application.properties`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceEventTests.java`

**Interfaces:**
- Consumes: `TraceEventDto`, `TraceStatus` (Task 1); `Redaction.scrub(...)` (Task 2); `ContentId.forTrace(...)` (Task 3).
- Produces:
  - `TraceProperties(boolean enabled, int maxOutputChars, long spoolMaxBytes, int spoolRetentionDays, long stopDrainThresholdBytes, int stopDrainThresholdEvents, List<String> toolDenylist, boolean skipUnchangedOutcomes, boolean recipeExtractionEnabled, int maxRecipesPerBatch, int maxLinkedSymbols, double recallBoost)` plus `TraceProperties.defaults()`
  - `TraceEvent(String id, String sessionId, String tool, String args, String output, TraceStatus status, Integer exitCode, String error, Instant occurredAt, boolean occurredAtFromReceipt, int redactionHits)`
  - `TraceEvent.from(String profileId, String sessionId, TraceEventDto dto, int budget, Path repoRoot, Path userHome, Instant receiptTime)` → `TraceEvent`
  - `TraceEvent.invocation()` → `String`, `TraceEvent.signature()` → `String`, `TraceEvent.signalLine()` → `String`

> `TraceProperties` is a standalone `@ConfigurationProperties` record rather than a component on `PieriaProperties.Ingestion` — see **Deviations From The Spec #2**. `@ConfigurationPropertiesScan` on `PieriaApplication` registers it with no further wiring.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceEventTests.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TraceEventTests {

  private static final Instant STARTED = Instant.parse("2026-08-29T10:00:00Z");
  private static final Instant ENDED = Instant.parse("2026-08-29T10:00:07Z");
  private static final Instant RECEIPT = Instant.parse("2026-08-29T12:00:00Z");
  private static final Path REPO = Path.of("/repo");
  private static final Path HOME = Path.of("/home/dev");

  private static TraceEvent from(TraceEventDto dto) {
    return TraceEvent.from("p1", "s1", dto, 4000, REPO, HOME, RECEIPT);
  }

  @Test
  void endedAtWinsAsTheEventTime() {
    TraceEvent event = from(new TraceEventDto(
      "Bash", "./gradlew test", "ok", TraceStatus.SUCCESS, 0, null, STARTED, ENDED));

    assertThat(event.occurredAt()).isEqualTo(ENDED);
    assertThat(event.occurredAtFromReceipt()).isFalse();
  }

  @Test
  void startedAtIsTheFirstFallback() {
    TraceEvent event = from(new TraceEventDto(
      "Bash", "./gradlew test", "ok", TraceStatus.SUCCESS, 0, null, STARTED, null));

    assertThat(event.occurredAt()).isEqualTo(STARTED);
    assertThat(event.occurredAtFromReceipt()).isFalse();
  }

  // A trace with no timestamps must be distinguishable from one that genuinely ran at ingest time,
  // or a late-drained spool misorders against supersession.
  @Test
  void receiptTimeIsTheLastResortAndIsFlagged() {
    TraceEvent event = from(new TraceEventDto(
      "Bash", "./gradlew test", "ok", TraceStatus.SUCCESS, 0, null, null, null));

    assertThat(event.occurredAt()).isEqualTo(RECEIPT);
    assertThat(event.occurredAtFromReceipt()).isTrue();
  }

  @Test
  void argsAndOutputAreRedactedAndPathsNormalized() {
    TraceEvent event = from(new TraceEventDto(
      "Bash", "cd /repo/src && TOKEN=abcdef123456 ./run.sh",
      "wrote /home/dev/.cache/x", TraceStatus.SUCCESS, 0, null, STARTED, ENDED));

    assertThat(event.args()).doesNotContain("abcdef123456");
    assertThat(event.args()).contains("./src");
    assertThat(event.output()).contains("~/.cache/x");
    assertThat(event.redactionHits()).isEqualTo(1);
  }

  @Test
  void outputIsCappedAtTheBudget() {
    TraceEvent event = TraceEvent.from("p1", "s1", new TraceEventDto(
      "Bash", "run", "z".repeat(10_000), TraceStatus.SUCCESS, 0, null, STARTED, ENDED),
      200, REPO, HOME, RECEIPT);

    assertThat(event.output().length()).isLessThan(400);
  }

  @Test
  void bashInvocationIsTheCommandAlone() {
    TraceEvent event = from(new TraceEventDto(
      "Bash", "./gradlew test", "", TraceStatus.SUCCESS, 0, null, STARTED, ENDED));

    assertThat(event.invocation()).isEqualTo("./gradlew test");
    assertThat(event.signature()).isEqualTo("gradlew-test");
  }

  @Test
  void nonBashInvocationNamesTheTool() {
    TraceEvent event = from(new TraceEventDto(
      "Edit", "src/Foo.java", "", TraceStatus.SUCCESS, null, null, STARTED, ENDED));

    assertThat(event.invocation()).isEqualTo("Edit src/Foo.java");
  }

  // The signal line is what a failure memory quotes, so error beats output and the first
  // non-blank line beats the rest.
  @Test
  void signalLinePrefersErrorOverOutput() {
    TraceEvent event = from(new TraceEventDto(
      "Bash", "./gradlew test", "compiling\nBUILD FAILED", TraceStatus.FAILURE, 1,
      "\n\nGroundingFilterTests > grounded FAILED\n  at Foo.java:1", STARTED, ENDED));

    assertThat(event.signalLine()).isEqualTo("GroundingFilterTests > grounded FAILED");
  }

  @Test
  void signalLineFallsBackToTheLastOutputLineThenToAPlaceholder() {
    TraceEvent withOutput = from(new TraceEventDto(
      "Bash", "x", "compiling\nBUILD FAILED\n", TraceStatus.FAILURE, 1, null, STARTED, ENDED));
    TraceEvent withNothing = from(new TraceEventDto(
      "Bash", "x", "  ", TraceStatus.FAILURE, 1, null, STARTED, ENDED));

    assertThat(withOutput.signalLine()).isEqualTo("BUILD FAILED");
    assertThat(withNothing.signalLine()).isEqualTo("no output captured");
  }

  @Test
  void idIsContentAddressedOverTheRedactedArgs() {
    TraceEventDto dto = new TraceEventDto(
      "Bash", "./gradlew test", "ok", TraceStatus.SUCCESS, 0, null, STARTED, ENDED);

    assertThat(from(dto).id()).isEqualTo(from(dto).id()).hasSize(32);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceEventTests'`
Expected: FAIL — compilation error, `TraceEvent` does not exist.

- [ ] **Step 3: Write TraceProperties**

Create `modules/daemon/src/main/java/dev/alvo/pieria/config/TraceProperties.java`:

```java
package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Execution-trace ingestion tuning: capture limits, spool bounds, noise rejection, and how much
 * model work a batch may do.
 *
 * <p>A standalone properties record rather than a component of {@code PieriaProperties.Ingestion},
 * which is constructed positionally in sixteen test files. This follows the same pattern as
 * {@link AuditProperties} and {@link ReminiscenceProperties} and keeps the property prefix the
 * design specifies.
 *
 * @param enabled                  master switch; when false the daemon accepts and discards traces,
 *                                 so the feature is off without uninstalling the hook
 * @param maxOutputChars           per-field truncation budget for {@code output}/{@code error}
 * @param spoolMaxBytes            spool file size above which the oldest half is dropped
 * @param spoolRetentionDays       age above which an abandoned spool file is swept
 * @param stopDrainThresholdBytes  spool size at which an end-of-turn Stop hook drains
 * @param stopDrainThresholdEvents spool event count at which an end-of-turn Stop hook drains
 * @param toolDenylist             tools whose <em>successful</em> calls carry no durable signal
 * @param skipUnchangedOutcomes    drop a trace whose active outcome already records the same status
 *                                 and error digest
 * @param recipeExtractionEnabled  whether the small model derives procedural instructions
 * @param maxRecipesPerBatch       cap on instructions derived from one ingest
 * @param maxLinkedSymbols         cap on code-index symbols linked into one trace memory
 * @param recallBoost              post-fusion multiplier for trace-sourced candidates
 *                                 ({@code 1.0} = off)
 */
@ConfigurationProperties(prefix = "pieria.ingestion.trace")
public record TraceProperties(
  @DefaultValue("true") boolean enabled,
  @DefaultValue("4000") int maxOutputChars,
  @DefaultValue("4194304") long spoolMaxBytes,
  @DefaultValue("7") int spoolRetentionDays,
  @DefaultValue("65536") long stopDrainThresholdBytes,
  @DefaultValue("50") int stopDrainThresholdEvents,
  @DefaultValue("Read,Grep,Glob,LS,TodoWrite,NotebookRead,WebSearch,WebFetch,Task")
  List<String> toolDenylist,
  @DefaultValue("true") boolean skipUnchangedOutcomes,
  @DefaultValue("true") boolean recipeExtractionEnabled,
  @DefaultValue("3") int maxRecipesPerBatch,
  @DefaultValue("10") int maxLinkedSymbols,
  @DefaultValue("1.0") double recallBoost) {

  public TraceProperties {
    maxOutputChars = Math.max(200, maxOutputChars);
    spoolMaxBytes = Math.max(4096L, spoolMaxBytes);
    spoolRetentionDays = Math.max(1, spoolRetentionDays);
    stopDrainThresholdBytes = Math.max(0L, stopDrainThresholdBytes);
    stopDrainThresholdEvents = Math.max(0, stopDrainThresholdEvents);
    toolDenylist = toolDenylist == null ? List.of() : List.copyOf(toolDenylist);
    maxRecipesPerBatch = Math.max(0, maxRecipesPerBatch);
    maxLinkedSymbols = Math.max(0, maxLinkedSymbols);
    recallBoost = Math.clamp(recallBoost, 0.0, 10.0);
  }

  /** Defaults, for call sites that construct this directly (tests, and the CLI hook's fallbacks). */
  public static TraceProperties defaults() {
    return new TraceProperties(true, 4000, 4_194_304L, 7, 65_536L, 50,
      List.of("Read", "Grep", "Glob", "LS", "TodoWrite", "NotebookRead", "WebSearch", "WebFetch",
        "Task"),
      true, true, 3, 10, 1.0);
  }
}
```

- [ ] **Step 4: Write TraceEvent**

Create `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceEvent.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.tools.Redaction;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A redacted, bounded, time-resolved tool call: the daemon's own view of an inbound
 * {@link TraceEventDto}.
 *
 * <p>It exists rather than reusing the DTO because three real transformations happen on the way in:
 * secrets and machine paths are scrubbed, oversized output is capped, and the event time is
 * resolved through an explicit fallback chain. Downstream stages never see the raw DTO.
 *
 * @param occurredAtFromReceipt whether {@code occurredAt} fell all the way through to the daemon's
 *                              clock, which the service counts and logs. A trace with no timestamps
 *                              must not be silently indistinguishable from one that genuinely ran
 *                              at ingest time.
 */
public record TraceEvent(
  String id,
  String sessionId,
  String tool,
  String args,
  String output,
  TraceStatus status,
  Integer exitCode,
  String error,
  Instant occurredAt,
  boolean occurredAtFromReceipt,
  int redactionHits) {

  private static final String NO_OUTPUT = "no output captured";

  /**
   * Build the domain event: scrub every free-text field, resolve the event time
   * ({@code endedAt} then {@code startedAt} then {@code receiptTime}), then content-address it.
   */
  public static TraceEvent from(String profileId,
                                String sessionId,
                                TraceEventDto dto,
                                int budget,
                                Path repoRoot,
                                Path userHome,
                                Instant receiptTime) {
    Redaction.Redacted args = Redaction.scrub(dto.args(), budget, repoRoot, userHome);
    Redaction.Redacted output = Redaction.scrub(dto.output(), budget, repoRoot, userHome);
    Redaction.Redacted error = Redaction.scrub(dto.error(), budget, repoRoot, userHome);

    boolean fromReceipt = dto.endedAt() == null && dto.startedAt() == null;
    Instant occurredAt = dto.endedAt() != null ? dto.endedAt()
      : dto.startedAt() != null ? dto.startedAt()
      : receiptTime;

    String id = ContentId.forTrace(
      profileId, sessionId, dto.tool(), args.text(), dto.status(), occurredAt);

    return new TraceEvent(
      id,
      sessionId,
      dto.tool(),
      args.text(),
      output.text(),
      dto.status(),
      dto.exitCode(),
      error.text(),
      occurredAt,
      fromReceipt,
      args.hits() + output.hits() + error.hits());
  }

  /**
   * How the call reads in prose. {@code Bash} args already name the program; every other tool needs
   * its own name to be legible.
   */
  public String invocation() {
    String trimmed = args == null ? "" : args.strip();
    if (trimmed.isEmpty()) {
      return tool;
    }
    return "Bash".equals(tool) ? trimmed : tool + " " + trimmed;
  }

  /** The grouping key both trace topic keys derive from. */
  public String signature() {
    return CommandSignature.of(tool, args);
  }

  /**
   * The one line worth quoting in a memory: the first non-blank line of {@code error}, else the
   * last non-blank line of {@code output}, else a placeholder. Error beats output because a build
   * tool's stderr names the failure while its stdout narrates progress.
   */
  public String signalLine() {
    String firstError = firstNonBlankLine(error);
    if (firstError != null) {
      return firstError;
    }
    String lastOutput = lastNonBlankLine(output);
    return lastOutput != null ? lastOutput : NO_OUTPUT;
  }

  private static String firstNonBlankLine(String text) {
    if (text == null) {
      return null;
    }
    for (String line : text.split("\n")) {
      String stripped = line.strip();
      if (!stripped.isEmpty()) {
        return stripped;
      }
    }
    return null;
  }

  private static String lastNonBlankLine(String text) {
    if (text == null) {
      return null;
    }
    String[] lines = text.split("\n");
    for (int i = lines.length - 1; i >= 0; i--) {
      String stripped = lines[i].strip();
      if (!stripped.isEmpty()) {
        return stripped;
      }
    }
    return null;
  }
}
```

- [ ] **Step 5: Add the properties to application.properties**

Append to `modules/daemon/src/main/resources/application.properties`, after the existing `pieria.ingestion.*` block:

```properties
# Execution-trace ingestion (Phase 12). Traces arrive on POST /ingest's `traces` field from a
# harness PostToolUse hook; enabled=false accepts and discards them so the feature can be turned
# off without uninstalling the hook.
pieria.ingestion.trace.enabled=true
pieria.ingestion.trace.max-output-chars=4000
pieria.ingestion.trace.spool-max-bytes=4194304
pieria.ingestion.trace.spool-retention-days=7
pieria.ingestion.trace.stop-drain-threshold-bytes=65536
pieria.ingestion.trace.stop-drain-threshold-events=50
pieria.ingestion.trace.tool-denylist=Read,Grep,Glob,LS,TodoWrite,NotebookRead,WebSearch,WebFetch,Task
pieria.ingestion.trace.skip-unchanged-outcomes=true
pieria.ingestion.trace.recipe-extraction-enabled=true
pieria.ingestion.trace.max-recipes-per-batch=3
pieria.ingestion.trace.max-linked-symbols=10
# Post-fusion multiplier for trace-sourced candidates; 1.0 disables the boost.
pieria.ingestion.trace.recall-boost=1.0
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceEventTests'`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/config/TraceProperties.java \
        modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceEvent.java \
        modules/daemon/src/main/resources/application.properties \
        modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceEventTests.java
git commit -m "feat(trace): add TraceProperties and the redacted TraceEvent domain record"
```

---

### Task 5: Relevance filter

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceRelevanceFilter.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceRelevanceFilterTests.java`

**Interfaces:**
- Consumes: `TraceEvent` (Task 4), `TraceProperties` (Task 4), `CommandSignature.errorDigest(String)` (Task 3), `Memory` / `MemoryType` from `dev.alvo.pieria.domain.memory`.
- Produces: `new TraceRelevanceFilter(TraceProperties)`; `filter(List<TraceEvent> events, Function<String, Optional<Memory>> activeOutcomeLookup)` → `TraceRelevanceFilter.Result(List<TraceEvent> kept, Map<String, Integer> droppedByRule)`.

> The active-outcome lookup is a `Function` rather than a store handle. This is not a test seam — the filter genuinely has no business owning a store, and the service that does own one is its only production caller.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceRelevanceFilterTests.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRelevanceFilterTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  private final TraceRelevanceFilter filter = new TraceRelevanceFilter(TraceProperties.defaults());

  private static TraceEvent event(String tool, String args, TraceStatus status, String error) {
    return new TraceEvent("id-" + tool + args + status, "s1", tool, args, "", status,
      status == TraceStatus.FAILURE ? 1 : 0, error, AT, false, 0);
  }

  private static TraceRelevanceFilter.Result run(TraceRelevanceFilter filter, TraceEvent... events) {
    return filter.filter(List.of(events), signature -> Optional.empty());
  }

  private static Memory activeOutcome(String status, String digest) {
    return Memory.of(MemoryType.EVENT, "stored outcome", "s0", "trace:outcome:gradlew-test",
      "{\"source\":\"trace\",\"status\":\"" + status + "\",\"error_digest\":\"" + digest + "\"}");
  }

  @Test
  void successfulDenylistedToolsAreDropped() {
    TraceRelevanceFilter.Result result = run(filter,
      event("Read", "src/Foo.java", TraceStatus.SUCCESS, null),
      event("Grep", "pattern", TraceStatus.SUCCESS, null));

    assertThat(result.kept()).isEmpty();
    assertThat(result.droppedByRule()).containsEntry("denylisted-tool", 2);
  }

  // A failing read is signal: the file is missing or unreadable, and that is worth remembering.
  @Test
  void failuresSurviveTheDenylist() {
    TraceRelevanceFilter.Result result = run(filter,
      event("Read", "missing.java", TraceStatus.FAILURE, "ENOENT"));

    assertThat(result.kept()).hasSize(1);
  }

  @Test
  void bashAndEditsAreAlwaysKept() {
    TraceRelevanceFilter.Result result = run(filter,
      event("Bash", "./gradlew test", TraceStatus.SUCCESS, null),
      event("Edit", "src/Foo.java", TraceStatus.SUCCESS, null),
      event("Write", "src/Bar.java", TraceStatus.SUCCESS, null));

    assertThat(result.kept()).hasSize(3);
  }

  // Only the last run of a command in a batch reflects the current state; earlier ones would
  // supersede each other in arrival order for no benefit.
  @Test
  void repeatsOfOneSignatureAndStatusCollapseToTheLast() {
    TraceEvent first = event("Bash", "./gradlew test", TraceStatus.SUCCESS, null);
    TraceEvent second = event("Bash", "./gradlew test --info", TraceStatus.SUCCESS, null);

    TraceRelevanceFilter.Result result = run(filter, first, second);

    assertThat(result.kept()).containsExactly(second);
    assertThat(result.droppedByRule()).containsEntry("in-batch-repeat", 1);
  }

  @Test
  void aFailureAndASuccessOfOneSignatureBothSurvive() {
    TraceEvent failed = event("Bash", "./gradlew test", TraceStatus.FAILURE, "boom");
    TraceEvent passed = event("Bash", "./gradlew test", TraceStatus.SUCCESS, null);

    assertThat(run(filter, failed, passed).kept()).containsExactly(failed, passed);
  }

  // Re-writing "still passing" every turn is churn with no new information.
  @Test
  void unchangedOutcomeIsSkippedAgainstTheActiveMemory() {
    TraceEvent passed = event("Bash", "./gradlew test", TraceStatus.SUCCESS, null);

    TraceRelevanceFilter.Result result =
      filter.filter(List.of(passed), signature -> Optional.of(activeOutcome("success", "none")));

    assertThat(result.kept()).isEmpty();
    assertThat(result.droppedByRule()).containsEntry("unchanged-outcome", 1);
  }

  @Test
  void aStatusChangeIsNeverSkipped() {
    TraceEvent failed = event("Bash", "./gradlew test", TraceStatus.FAILURE, "boom");

    assertThat(filter.filter(List.of(failed), s -> Optional.of(activeOutcome("success", "none")))
      .kept()).hasSize(1);
  }

  @Test
  void aDifferentFailureIsNeverSkipped() {
    TraceEvent failed = event("Bash", "./gradlew test", TraceStatus.FAILURE, "AssertionError");
    Memory active = activeOutcome("failure", CommandSignature.errorDigest("NullPointerException"));

    assertThat(filter.filter(List.of(failed), s -> Optional.of(active)).kept()).hasSize(1);
  }

  // A hand-written memory sharing the key, or one written before the digest existed, must not
  // silently swallow a real result.
  @Test
  void anActiveMemoryWithoutADigestIsTreatedAsChanged() {
    TraceEvent passed = event("Bash", "./gradlew test", TraceStatus.SUCCESS, null);
    Memory legacy = Memory.of(MemoryType.EVENT, "old", "s0", "trace:outcome:gradlew-test", "{}");

    assertThat(filter.filter(List.of(passed), s -> Optional.of(legacy)).kept()).hasSize(1);
  }

  @Test
  void skipUnchangedCanBeDisabled() {
    TraceProperties d = TraceProperties.defaults();
    TraceRelevanceFilter lenient = new TraceRelevanceFilter(new TraceProperties(
      d.enabled(), d.maxOutputChars(), d.spoolMaxBytes(), d.spoolRetentionDays(),
      d.stopDrainThresholdBytes(), d.stopDrainThresholdEvents(), d.toolDenylist(),
      false, d.recipeExtractionEnabled(), d.maxRecipesPerBatch(), d.maxLinkedSymbols(),
      d.recallBoost()));

    TraceEvent passed = event("Bash", "./gradlew test", TraceStatus.SUCCESS, null);

    assertThat(lenient.filter(List.of(passed), s -> Optional.of(activeOutcome("success", "none")))
      .kept()).hasSize(1);
  }

  @Test
  void emptyInputYieldsEmptyResult() {
    assertThat(filter.filter(List.of(), s -> Optional.empty()).kept()).isEmpty();
    assertThat(filter.filter(null, s -> Optional.empty()).kept()).isEmpty();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceRelevanceFilterTests'`
Expected: FAIL — compilation error, `TraceRelevanceFilter` does not exist.

- [ ] **Step 3: Write TraceRelevanceFilter**

Create `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceRelevanceFilter.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.domain.memory.Memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic noise rejection ahead of memory derivation. Every rule is a plain predicate: no
 * model is consulted about whether a trace is interesting, because the judgment ("did this fail",
 * "have we already recorded this outcome") is mechanical.
 *
 * <p>Rules, in order, first match wins:
 * <ol>
 *   <li><b>keep</b> any failure, whatever the tool — a failing read is signal;</li>
 *   <li><b>drop</b> a successful call to a denylisted read-only tool;</li>
 *   <li><b>drop</b> all but the last occurrence of a {@code (signature, status)} in the batch;</li>
 *   <li><b>drop</b> a trace whose active outcome already records the same status and error
 *       digest.</li>
 * </ol>
 */
public class TraceRelevanceFilter {

  /** Separator for the in-batch dedupe key. Both halves are slug/enum text, so this is unambiguous. */
  private static final String KEY_SEPARATOR = "|";

  /** Tools whose calls always carry signal, regardless of the denylist. */
  private static final Set<String> ALWAYS_KEPT =
    Set.of("bash", "edit", "write", "multiedit", "notebookedit");

  private static final Pattern STATUS_FIELD = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern DIGEST_FIELD =
    Pattern.compile("\"error_digest\"\\s*:\\s*\"([^\"]*)\"");

  private final TraceProperties properties;
  private final Set<String> denylist;

  public TraceRelevanceFilter(TraceProperties properties) {
    this.properties = properties;
    this.denylist = new LinkedHashSet<>(
      properties.toolDenylist().stream().map(tool -> tool.toLowerCase(Locale.ROOT)).toList());
  }

  /** Survivors plus a per-rule tally of what was dropped, for the ingest log. */
  public record Result(List<TraceEvent> kept, Map<String, Integer> droppedByRule) {
  }

  /**
   * @param events              the batch, in arrival order
   * @param activeOutcomeLookup signature to the active {@code trace:outcome:<signature>} memory, if
   *                            any; supplied by the service, which owns the store
   */
  public Result filter(List<TraceEvent> events,
                       Function<String, Optional<Memory>> activeOutcomeLookup) {
    if (events == null || events.isEmpty()) {
      return new Result(List.of(), Map.of());
    }
    Map<String, Integer> dropped = new LinkedHashMap<>();

    // Rules 1-2: per-event admission.
    List<TraceEvent> admitted = new ArrayList<>();
    for (TraceEvent event : events) {
      if (event.status() == TraceStatus.FAILURE || isAlwaysKept(event) || !isDenylisted(event)) {
        admitted.add(event);
      } else {
        dropped.merge("denylisted-tool", 1, Integer::sum);
      }
    }

    // Rule 3: keep only the last occurrence of each (signature, status) in the batch.
    Map<String, TraceEvent> lastPerKey = new LinkedHashMap<>();
    for (TraceEvent event : admitted) {
      lastPerKey.put(event.signature() + KEY_SEPARATOR + event.status().wire(), event);
    }
    int collapsed = admitted.size() - lastPerKey.size();
    if (collapsed > 0) {
      dropped.merge("in-batch-repeat", collapsed, Integer::sum);
    }

    // Rule 4: skip an outcome the store already records unchanged.
    List<TraceEvent> kept = new ArrayList<>();
    for (TraceEvent event : lastPerKey.values()) {
      if (properties.skipUnchangedOutcomes() && isUnchanged(event, activeOutcomeLookup)) {
        dropped.merge("unchanged-outcome", 1, Integer::sum);
      } else {
        kept.add(event);
      }
    }
    return new Result(List.copyOf(kept), Map.copyOf(dropped));
  }

  private boolean isAlwaysKept(TraceEvent event) {
    return event.tool() != null && ALWAYS_KEPT.contains(event.tool().toLowerCase(Locale.ROOT));
  }

  private boolean isDenylisted(TraceEvent event) {
    return event.tool() != null && denylist.contains(event.tool().toLowerCase(Locale.ROOT));
  }

  /**
   * Whether the active outcome for this signature already says the same thing. Compares status and
   * the error digest rather than raw text, so a recompile that shifts a stack frame does not read
   * as a new outcome. An active memory carrying neither field is treated as changed: it may be
   * hand-written or predate the digest, and swallowing a real result would be worse than a
   * redundant write.
   */
  private boolean isUnchanged(TraceEvent event,
                              Function<String, Optional<Memory>> activeOutcomeLookup) {
    Optional<Memory> active = activeOutcomeLookup.apply(event.signature());
    if (active.isEmpty()) {
      return false;
    }
    String payload = active.get().payload();
    String storedStatus = field(STATUS_FIELD, payload);
    String storedDigest = field(DIGEST_FIELD, payload);
    if (storedStatus == null || storedDigest == null) {
      return false;
    }
    String incomingDigest = CommandSignature.errorDigest(
      event.error() != null && !event.error().isBlank() ? event.error() : event.output());
    return storedStatus.equals(event.status().wire()) && storedDigest.equals(incomingDigest);
  }

  private static String field(Pattern pattern, String payload) {
    if (payload == null) {
      return null;
    }
    Matcher matcher = pattern.matcher(payload);
    return matcher.find() ? matcher.group(1) : null;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceRelevanceFilterTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceRelevanceFilter.java \
        modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceRelevanceFilterTests.java
git commit -m "feat(trace): add deterministic TraceRelevanceFilter"
```

---

### Task 6: Deterministic event memories

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceMemoryFactory.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceMemoryFactoryTests.java`

**Interfaces:**
- Consumes: `TraceEvent` (Task 4), `CommandSignature.errorDigest(String)` (Task 3), `Memory`/`MemoryType`/`MemoryTimes` from `dev.alvo.pieria.domain.memory`.
- Produces: `TraceMemoryFactory.OUTCOME_KEY_PREFIX` (`"trace:outcome:"`), `TraceMemoryFactory.RECIPE_KEY_PREFIX` (`"trace:recipe:"`), `TraceMemoryFactory.SOURCE_TRACE` (`"trace"`); `TraceMemoryFactory.outcome(TraceEvent event, List<String> symbolIds)` → `Memory`; `TraceMemoryFactory.recipe(String statement, String signature, java.time.Instant statedAt, List<String> symbolIds)` → `Memory`; `TraceMemoryFactory.rawMessageContent(TraceEvent event)` → `String`.

> No model call happens anywhere in this class. A trace already states the command, the exit code, and the error text, so there is nothing to infer — and therefore nothing for a verify stage to catch. `symbolIds` is written under exactly that key (not `symbol_ids`) because `SqliteMemoryStore.findCodeMemoriesBySymbolIds` queries `payload.$.symbolIds`; renaming it would silently break the code-channel join.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceMemoryFactoryTests.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryTimes;
import dev.alvo.pieria.domain.memory.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceMemoryFactoryTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:07Z");

  private static TraceEvent event(TraceStatus status, Integer exitCode, String error, String output) {
    return new TraceEvent("tid", "s1", "Bash", "./gradlew test", output, status, exitCode, error,
      AT, false, 0);
  }

  @Test
  void failureContentQuotesTheCommandExitCodeAndSignalLine() {
    Memory memory = TraceMemoryFactory.outcome(
      event(TraceStatus.FAILURE, 1, "GroundingFilterTests > grounded FAILED", ""), List.of());

    assertThat(memory.type()).isEqualTo(MemoryType.EVENT);
    assertThat(memory.content())
      .isEqualTo("`./gradlew test` failed (exit 1): GroundingFilterTests > grounded FAILED");
  }

  @Test
  void successContentIsTerse() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.SUCCESS, 0, null, "ok"), List.of());

    assertThat(memory.content()).isEqualTo("`./gradlew test` succeeded (exit 0)");
  }

  @Test
  void unknownStatusSaysSo() {
    Memory memory =
      TraceMemoryFactory.outcome(event(TraceStatus.UNKNOWN, null, null, ""), List.of());

    assertThat(memory.content()).isEqualTo("`./gradlew test` ran; outcome unknown");
  }

  @Test
  void missingExitCodeIsOmittedRatherThanGuessed() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.FAILURE, null, "boom", ""), List.of());

    assertThat(memory.content()).isEqualTo("`./gradlew test` failed: boom");
  }

  // The key is what makes run n+1 supersede run n through the existing machinery.
  @Test
  void outcomeIsKeyedBySignature() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.SUCCESS, 0, null, ""), List.of());

    assertThat(memory.topicKey()).isEqualTo("trace:outcome:gradlew-test");
  }

  @Test
  void payloadCarriesTheTraceProvenanceContract() {
    Memory memory = TraceMemoryFactory.outcome(
      event(TraceStatus.FAILURE, 1, "boom", ""), List.of("sym1", "sym2"));

    assertThat(memory.payload())
      .contains("\"source\":\"trace\"")
      .contains("\"tool\":\"Bash\"")
      .contains("\"command\":\"./gradlew test\"")
      .contains("\"status\":\"failure\"")
      .contains("\"exit_code\":1")
      .contains("\"error_digest\":\"" + CommandSignature.errorDigest("boom") + "\"")
      .contains("\"symbolIds\":[\"sym1\",\"sym2\"]");
  }

  // Both times come from the trace, never from the store clock: occurred_at because the command
  // genuinely ran then, stated_at because supersession ordering reads it.
  @Test
  void bothTimesComeFromTheTraceNotTheStoreClock() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.SUCCESS, 0, null, ""), List.of());

    assertThat(memory.payload()).contains("\"occurred_at\":\"2026-08-29T10:00:07Z\"");
    assertThat(memory.payload()).contains("\"stated_at\":\"2026-08-29T10:00:07Z\"");
    assertThat(MemoryTimes.knowledgeTime(memory)).isEqualTo(AT);
    assertThat(MemoryTimes.anchor(memory)).isEqualTo(AT.atZone(java.time.ZoneOffset.UTC).toLocalDate());
  }

  @Test
  void emptySymbolIdsAreOmittedFromThePayload() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.SUCCESS, 0, null, ""), List.of());

    assertThat(memory.payload()).doesNotContain("symbolIds");
  }

  // embed_text pairs the declarative statement with the questions an agent actually asks, so a
  // procedural trace surfaces under natural phrasing rather than only under its command string.
  @Test
  void embedTextAddsDeterministicInterrogatives() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.FAILURE, 1, "boom", ""), List.of());

    assertThat(memory.embedText())
      .contains(memory.content())
      .contains("how do I run `./gradlew test`")
      .contains("does `./gradlew test` pass")
      .contains("why does `./gradlew test` fail");
  }

  @Test
  void successEmbedTextOmitsTheFailureQuestion() {
    Memory memory = TraceMemoryFactory.outcome(event(TraceStatus.SUCCESS, 0, null, ""), List.of());

    assertThat(memory.embedText()).doesNotContain("why does");
  }

  @Test
  void recipeIsAKeyedInstruction() {
    Memory memory = TraceMemoryFactory.recipe(
      "Tests in this repo are run with ./gradlew test.", "gradlew-test", AT, List.of("sym1"));

    assertThat(memory.type()).isEqualTo(MemoryType.INSTRUCTION);
    assertThat(memory.topicKey()).isEqualTo("trace:recipe:gradlew-test");
    assertThat(memory.content()).isEqualTo("Tests in this repo are run with ./gradlew test.");
    assertThat(memory.payload()).contains("\"source\":\"trace\"").contains("\"symbolIds\":[\"sym1\"]");
    assertThat(MemoryTimes.knowledgeTime(memory)).isEqualTo(AT);
  }

  // The raw row is retrieval evidence; MessageFtsChannel searches it, so it must carry the
  // command and the output, not just a summary label.
  @Test
  void rawMessageContentCarriesCommandStatusAndOutput() {
    String raw = TraceMemoryFactory.rawMessageContent(
      event(TraceStatus.FAILURE, 1, "boom", "compiling"));

    assertThat(raw)
      .contains("Bash")
      .contains("./gradlew test")
      .contains("failure")
      .contains("compiling")
      .contains("boom");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceMemoryFactoryTests'`
Expected: FAIL — compilation error, `TraceMemoryFactory` does not exist.

- [ ] **Step 3: Write TraceMemoryFactory**

Create `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceMemoryFactory.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryTimes;
import dev.alvo.pieria.domain.memory.MemoryType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds durable memories from traces deterministically, in Java, with no model call.
 *
 * <p>A trace already states the command, the exit code, and the error text. There is nothing to
 * infer, so there is nothing for the verify stage to catch — these memories are grounded by
 * construction. The model's only job on the trace path is generalizing a <em>recipe</em> from a
 * sequence, which {@code TraceRecipeExtractor} owns.
 *
 * <p>Outcomes are keyed on the command signature so the existing supersession machinery keeps one
 * active row per command: run {@code n+1} demotes run {@code n} to history and drops its vector in
 * the same transaction.
 */
public final class TraceMemoryFactory {

  /** Payload marker distinguishing trace-derived memories from conversational ones. */
  public static final String SOURCE_TRACE = "trace";

  public static final String OUTCOME_KEY_PREFIX = "trace:outcome:";
  public static final String RECIPE_KEY_PREFIX = "trace:recipe:";

  /**
   * Payload key holding resolved code-index symbol ids. Must stay exactly this spelling:
   * {@code SqliteMemoryStore.findCodeMemoriesBySymbolIds} queries {@code payload.$.symbolIds}, and
   * renaming it would silently cut trace memories out of the code channels.
   */
  private static final String SYMBOL_IDS = "symbolIds";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private TraceMemoryFactory() {
  }

  /** The factual {@code event} memory for one trace. */
  public static Memory outcome(TraceEvent event, List<String> symbolIds) {
    String content = outcomeContent(event);
    ObjectNode payload = basePayload(event.occurredAt(), symbolIds);
    payload.put("tool", event.tool());
    payload.put("command", event.invocation());
    payload.put("status", event.status().wire());
    if (event.exitCode() != null) {
      payload.put("exit_code", event.exitCode());
    }
    payload.put("error_digest", CommandSignature.errorDigest(digestSource(event)));

    Memory memory = Memory.of(
      MemoryType.EVENT, content, event.sessionId(),
      OUTCOME_KEY_PREFIX + event.signature(), payload.toString());

    return withEmbedText(memory, embedText(content, event.invocation(),
      event.status() == TraceStatus.FAILURE));
  }

  /** A model-derived procedural {@code instruction}, keyed so a changed recipe supersedes. */
  public static Memory recipe(String statement, String signature, Instant statedAt,
                              List<String> symbolIds) {
    ObjectNode payload = basePayload(statedAt, symbolIds);
    Memory memory = Memory.of(
      MemoryType.INSTRUCTION, statement, null, RECIPE_KEY_PREFIX + signature, payload.toString());
    return withEmbedText(memory, statement);
  }

  /**
   * The raw evidence row stored in {@code messages} under role {@code "tool"}. Carries the command
   * and its output verbatim, because {@code MessageFtsChannel} searches this text.
   */
  public static String rawMessageContent(TraceEvent event) {
    StringBuilder text = new StringBuilder()
      .append(event.tool()).append(' ').append(event.args() == null ? "" : event.args())
      .append("\nstatus: ").append(event.status().wire());
    if (event.exitCode() != null) {
      text.append(" (exit ").append(event.exitCode()).append(')');
    }
    if (event.output() != null && !event.output().isBlank()) {
      text.append("\noutput:\n").append(event.output());
    }
    if (event.error() != null && !event.error().isBlank()) {
      text.append("\nerror:\n").append(event.error());
    }
    return text.toString();
  }

  private static String outcomeContent(TraceEvent event) {
    String invocation = "`" + event.invocation() + "`";
    String exitPart = event.exitCode() == null ? "" : " (exit " + event.exitCode() + ")";
    return switch (event.status()) {
      case FAILURE -> invocation + " failed" + exitPart + ": " + event.signalLine();
      case SUCCESS -> invocation + " succeeded" + exitPart;
      case UNKNOWN -> invocation + " ran; outcome unknown";
    };
  }

  /** The text the error digest is taken from: stderr when present, else whatever stdout carried. */
  private static String digestSource(TraceEvent event) {
    return event.error() != null && !event.error().isBlank() ? event.error() : event.output();
  }

  /**
   * The payload fields every trace memory carries. Both times come from the trace, never from the
   * store clock: {@code occurred_at} because the command genuinely ran then, {@code stated_at}
   * because {@code MemoryTimes.knowledgeTime} reads it to order supersession — a spool drained
   * hours later must still order by when the command ran.
   */
  private static ObjectNode basePayload(Instant at, List<String> symbolIds) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("source", SOURCE_TRACE);
    if (at != null) {
      payload.put(MemoryTimes.OCCURRED_AT, at.toString());
      payload.put(MemoryTimes.STATED_AT, at.toString());
    }
    if (symbolIds != null && !symbolIds.isEmpty()) {
      ArrayNode ids = payload.putArray(SYMBOL_IDS);
      symbolIds.forEach(ids::add);
    }
    return payload;
  }

  /**
   * Pair the declarative statement with the questions an agent actually asks. These are fixed
   * templates, not model output — the point is that "how do I run the tests" reaches a memory whose
   * content is a command line.
   */
  private static String embedText(String content, String invocation, boolean failed) {
    List<String> lines = new ArrayList<>();
    lines.add(content);
    lines.add("how do I run `" + invocation + "`");
    lines.add("does `" + invocation + "` pass");
    lines.add("what command runs `" + invocation + "`");
    if (failed) {
      lines.add("why does `" + invocation + "` fail");
    }
    return String.join("\n", lines);
  }

  private static Memory withEmbedText(Memory memory, String embedText) {
    return new Memory(memory.id(), memory.sessionId(), memory.type(), memory.content(),
      memory.topicKey(), memory.supersedes(), memory.superseded(), memory.payload(),
      embedText, memory.createdAt());
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceMemoryFactoryTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceMemoryFactory.java \
        modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceMemoryFactoryTests.java
git commit -m "feat(trace): derive keyed event memories from traces without a model call"
```

---

### Task 7: Graph fragment from a trace

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceGraphBuilder.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceGraphBuilderTests.java`

**Interfaces:**
- Consumes: `TraceEvent` (Task 4); `GraphFragment`, `GraphFragment.EdgeTriple`, `Entity`, `EntityNormalizer` from `dev.alvo.pieria.domain.graph`; `PieriaProperties` for the per-memory caps.
- Produces: `new TraceGraphBuilder(int maxEntities, int maxTriples)`; `build(TraceEvent event)` → `GraphFragment`.

> Entity types are `command`, `tool`, `test`, `file`, `module`. The feature description's "build tool" is **not** a separate type — Gradle, Maven, and npm are `tool` nodes distinguished by name, not by a type that only ever holds three values. Names go through `EntityNormalizer` before ids are computed, matching how every other graph writer works.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceGraphBuilderTests.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.domain.graph.GraphFragment;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TraceGraphBuilderTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  private final TraceGraphBuilder builder = new TraceGraphBuilder(8, 8);

  private static TraceEvent event(String tool, String args, TraceStatus status, String error) {
    return new TraceEvent("tid", "s1", tool, args, "", status, status == TraceStatus.FAILURE ? 1 : 0,
      error, AT, false, 0);
  }

  @Test
  void everyTraceLinksItsToolToItsCommand() {
    GraphFragment fragment =
      builder.build(event("Bash", "./gradlew test", TraceStatus.SUCCESS, null));

    assertThat(fragment.triples())
      .containsExactly(new GraphFragment.EdgeTriple(
        "bash", "tool", "invoked", "gradlew test", "command"));
  }

  @Test
  void aFailingTestIsLinkedToTheCommandThatRanIt() {
    GraphFragment fragment = builder.build(event("Bash", "./gradlew test", TraceStatus.FAILURE,
      "GroundingFilterTests > grounded FAILED"));

    assertThat(fragment.triples()).contains(new GraphFragment.EdgeTriple(
      "gradlew test", "command", "failed_in", "groundingfiltertests", "test"));
  }

  // A passing run must not claim a failure edge, or "why did X fail" retrieves a green build.
  @Test
  void aPassingRunEmitsNoFailureEdge() {
    GraphFragment fragment = builder.build(event("Bash", "./gradlew test", TraceStatus.SUCCESS,
      "GroundingFilterTests > grounded FAILED"));

    assertThat(fragment.triples())
      .noneMatch(triple -> triple.relation().equals("failed_in"));
  }

  @Test
  void aModuleQualifiedTaskLinksItsModule() {
    GraphFragment fragment =
      builder.build(event("Bash", "./gradlew :daemon:test", TraceStatus.SUCCESS, null));

    assertThat(fragment.triples()).contains(new GraphFragment.EdgeTriple(
      "gradlew :daemon:test", "command", "validates", "daemon", "module"));
  }

  @Test
  void anEditLinksTheFileItTouched() {
    GraphFragment fragment =
      builder.build(event("Edit", "modules/daemon/src/Foo.java", TraceStatus.SUCCESS, null));

    assertThat(fragment.triples()).contains(new GraphFragment.EdgeTriple(
      "edit modules/daemon/src/foo.java", "command", "touched",
      "modules/daemon/src/foo.java", "file"));
  }

  @Test
  void tripleCountIsCapped() {
    TraceGraphBuilder tight = new TraceGraphBuilder(2, 1);

    GraphFragment fragment = tight.build(event("Bash", "./gradlew :daemon:test",
      TraceStatus.FAILURE, "GroundingFilterTests > grounded FAILED"));

    assertThat(fragment.triples()).hasSize(1);
  }

  @Test
  void aTraceWithNoArgsYieldsAnEmptyFragment() {
    assertThat(builder.build(event("Bash", "  ", TraceStatus.SUCCESS, null)).isEmpty()).isTrue();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceGraphBuilderTests'`
Expected: FAIL — compilation error, `TraceGraphBuilder` does not exist.

- [ ] **Step 3: Write TraceGraphBuilder**

Create `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceGraphBuilder.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.domain.graph.EntityNormalizer;
import dev.alvo.pieria.domain.graph.GraphFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one trace into the entity/relation fragment stored alongside its memory, so the Phase 8
 * graph channel co-retrieves related commands, tests, and files.
 *
 * <p>Entity types are {@code command}, {@code tool}, {@code test}, {@code file}, and {@code module}.
 * The feature brief's "build tool" is deliberately not its own type: Gradle, Maven, and npm are
 * {@code tool} nodes distinguished by name, and a type that only ever holds three values buys
 * nothing.
 *
 * <p>Purely deterministic — no model is asked what a command relates to.
 */
public class TraceGraphBuilder {

  private static final String TYPE_COMMAND = "command";
  private static final String TYPE_TOOL = "tool";
  private static final String TYPE_TEST = "test";
  private static final String TYPE_FILE = "file";
  private static final String TYPE_MODULE = "module";

  /** Gradle's failure line: {@code SomeTests > someCase FAILED}. */
  private static final Pattern GRADLE_TEST_FAILURE =
    Pattern.compile("([A-Za-z_][A-Za-z0-9_.$]*)\\s*>\\s*[^\\n]*?\\bFAILED\\b");

  /** A module-qualified Gradle task: {@code :daemon:test}. */
  private static final Pattern GRADLE_MODULE_TASK = Pattern.compile(":([A-Za-z0-9_-]+):");

  /** A source path with a recognized extension. */
  private static final Pattern SOURCE_PATH =
    Pattern.compile("[\\w./-]+\\.(?:java|kt|scala|js|ts|tsx|scss|py|go|rs)");

  private final int maxEntities;
  private final int maxTriples;

  public TraceGraphBuilder(int maxEntities, int maxTriples) {
    this.maxEntities = Math.max(1, maxEntities);
    this.maxTriples = Math.max(1, maxTriples);
  }

  public GraphFragment build(TraceEvent event) {
    String command = EntityNormalizer.normalizeName(commandName(event));
    if (command.isEmpty()) {
      return GraphFragment.empty();
    }
    List<GraphFragment.EdgeTriple> triples = new ArrayList<>();

    String tool = EntityNormalizer.normalizeName(event.tool());
    if (!tool.isEmpty()) {
      triples.add(triple(tool, TYPE_TOOL, "invoked", command, TYPE_COMMAND));
    }

    // Only a failure may claim a failure edge, or "why did X fail" would retrieve a green build.
    if (event.status() == TraceStatus.FAILURE) {
      for (String test : matches(GRADLE_TEST_FAILURE, event.error(), event.output())) {
        triples.add(triple(command, TYPE_COMMAND, "failed_in",
          EntityNormalizer.normalizeName(test), TYPE_TEST));
      }
    }

    for (String module : matches(GRADLE_MODULE_TASK, event.args())) {
      triples.add(triple(command, TYPE_COMMAND, "validates",
        EntityNormalizer.normalizeName(module), TYPE_MODULE));
    }

    if (isEditingTool(event.tool())) {
      for (String path : matches(SOURCE_PATH, event.args())) {
        triples.add(triple(command, TYPE_COMMAND, "touched",
          EntityNormalizer.normalizeName(path), TYPE_FILE));
      }
    }

    List<GraphFragment.EdgeTriple> capped =
      triples.size() <= maxTriples ? triples : triples.subList(0, maxTriples);
    GraphFragment fragment = new GraphFragment(List.of(), capped);
    return fragment.allEntities().size() > maxEntities
      ? new GraphFragment(List.of(), capped.subList(0, Math.max(1, maxEntities / 2)))
      : fragment;
  }

  /** The command node's name: the invocation minus a leading {@code ./}, lower-cased downstream. */
  private static String commandName(TraceEvent event) {
    String invocation = event.invocation();
    return invocation == null ? "" : invocation.replace("./", "").strip();
  }

  private static boolean isEditingTool(String tool) {
    if (tool == null) {
      return false;
    }
    return switch (tool.toLowerCase(java.util.Locale.ROOT)) {
      case "edit", "write", "multiedit", "notebookedit" -> true;
      default -> false;
    };
  }

  private static GraphFragment.EdgeTriple triple(String source, String sourceType, String relation,
                                                 String target, String targetType) {
    return new GraphFragment.EdgeTriple(source, sourceType, relation, target, targetType);
  }

  /** First capture group of every match across the given texts, deduped, in order. */
  private static List<String> matches(Pattern pattern, String... texts) {
    List<String> found = new ArrayList<>();
    for (String text : texts) {
      if (text == null || text.isBlank()) {
        continue;
      }
      Matcher matcher = pattern.matcher(text);
      while (matcher.find()) {
        String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
        if (value != null && !value.isBlank() && !found.contains(value)) {
          found.add(value);
        }
      }
    }
    return found;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceGraphBuilderTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceGraphBuilder.java \
        modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceGraphBuilderTests.java
git commit -m "feat(trace): emit command/tool/test/file/module graph fragments from traces"
```

---

### Task 8: Code-index linking

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceCodeLinker.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceCodeLinkerTests.java`

**Interfaces:**
- Consumes: `TraceEvent` (Task 4); `CodeIndexStore.findSymbolsByQualifiedName(String, List<String>, int)` and `findSymbolsByName(String, List<String>, int)`; `CodeSymbol` from `dev.alvo.pieria.domain.code`.
- Produces: `new TraceCodeLinker(CodeIndexStore store, int maxLinkedSymbols)`; `link(String profileId, TraceEvent event)` → `List<String>` symbol ids.

> Resolution is qualified-name first, then bare name. Qualified names come from stack frames and are unambiguous; bare names come from test-failure lines and can collide, so they are the fallback. A profile with no code index resolves nothing and the linker returns an empty list — never an error.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceCodeLinkerTests.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.domain.code.CodeSymbol;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.storage.CodeIndexStore;
import dev.alvo.pieria.storage.NoOpCodeIndexStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceCodeLinkerTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  /** Records what was asked for and answers from a fixed table; no model, no database. */
  private static final class RecordingStore extends NoOpCodeIndexStore {
    final List<String> qualifiedQueries = new ArrayList<>();
    final List<String> nameQueries = new ArrayList<>();
    List<CodeSymbol> qualifiedHits = List.of();
    List<CodeSymbol> nameHits = List.of();

    @Override
    public List<CodeSymbol> findSymbolsByQualifiedName(String profileId, List<String> names, int limit) {
      qualifiedQueries.addAll(names);
      return qualifiedHits;
    }

    @Override
    public List<CodeSymbol> findSymbolsByName(String profileId, List<String> names, int limit) {
      nameQueries.addAll(names);
      return nameHits;
    }
  }

  // CodeSymbol's canonical constructor, in order:
  // (id, profileId, fileId, kind, name, qualifiedName, signature, visibility,
  //  startLine, endLine, language, parentSymbolId, path)
  private static CodeSymbol symbol(String id, String qualifiedName) {
    return new CodeSymbol(id, "p1", "file1", CodeSymbolKind.CLASS, qualifiedName, qualifiedName,
      null, "public", 1, 2, "java", null, "src/Foo.java");
  }

  private static TraceEvent event(String error) {
    return new TraceEvent("tid", "s1", "Bash", "./gradlew test", "", TraceStatus.FAILURE, 1,
      error, AT, false, 0);
  }

  @Test
  void javaStackFramesResolveByQualifiedName() {
    RecordingStore store = new RecordingStore();
    store.qualifiedHits = List.of(symbol("sym1", "dev.alvo.Foo.bar"));

    List<String> ids = new TraceCodeLinker(store, 10)
      .link("p1", event("at dev.alvo.Foo.bar(Foo.java:52)"));

    assertThat(store.qualifiedQueries).contains("dev.alvo.Foo.bar");
    assertThat(ids).containsExactly("sym1");
  }

  @Test
  void gradleFailureLinesResolveByBareName() {
    RecordingStore store = new RecordingStore();
    store.nameHits = List.of(symbol("sym2", "dev.alvo.GroundingFilterTests"));

    List<String> ids = new TraceCodeLinker(store, 10)
      .link("p1", event("GroundingFilterTests > grounded FAILED"));

    assertThat(store.nameQueries).contains("GroundingFilterTests");
    assertThat(ids).containsExactly("sym2");
  }

  @Test
  void bareSourcePathsAreResolvedByFileName() {
    RecordingStore store = new RecordingStore();
    store.nameHits = List.of(symbol("sym3", "dev.alvo.Redaction"));

    new TraceCodeLinker(store, 10).link("p1", event("error in modules/shared/Redaction.java"));

    assertThat(store.nameQueries).contains("Redaction");
  }

  @Test
  void resultsAreDedupedAndCapped() {
    RecordingStore store = new RecordingStore();
    store.qualifiedHits = List.of(symbol("s1", "a"), symbol("s1", "a"), symbol("s2", "b"),
      symbol("s3", "c"));

    List<String> ids = new TraceCodeLinker(store, 2)
      .link("p1", event("at dev.alvo.Foo.bar(Foo.java:1)"));

    assertThat(ids).containsExactly("s1", "s2");
  }

  // A profile that was never code-indexed must degrade to "no links", never to an error.
  @Test
  void aProfileWithNoCodeIndexResolvesNothing() {
    List<String> ids = new TraceCodeLinker(new NoOpCodeIndexStore(), 10)
      .link("p1", event("at dev.alvo.Foo.bar(Foo.java:52)"));

    assertThat(ids).isEmpty();
  }

  @Test
  void aTraceWithNoCodeReferencesQueriesNothing() {
    RecordingStore store = new RecordingStore();

    List<String> ids = new TraceCodeLinker(store, 10).link("p1", event("connection refused"));

    assertThat(ids).isEmpty();
    assertThat(store.qualifiedQueries).isEmpty();
    assertThat(store.nameQueries).isEmpty();
  }

  @Test
  void aZeroCapDisablesLinking() {
    RecordingStore store = new RecordingStore();
    store.qualifiedHits = List.of(symbol("s1", "a"));

    assertThat(new TraceCodeLinker(store, 0).link("p1", event("at dev.alvo.Foo.bar(Foo.java:1)")))
      .isEmpty();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceCodeLinkerTests'`
Expected: FAIL — compilation error, `TraceCodeLinker` does not exist.

- [ ] **Step 3: Write TraceCodeLinker**

Create `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceCodeLinker.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.domain.code.CodeSymbol;
import dev.alvo.pieria.storage.CodeIndexStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the code a trace talks about against the Phase 13 index, so "why did this test fail"
 * retrieves the trace <em>and</em> the failing class.
 *
 * <p>The ids this returns are written to the memory payload under {@code symbolIds} — the key
 * {@code SqliteMemoryStore.findCodeMemoriesBySymbolIds} already queries. That is what makes the
 * join free: no schema change, no new channel.
 *
 * <p>Extraction is deterministic regex; resolution is qualified-name first (stack frames are
 * unambiguous), then bare name (test-failure lines can collide, so they are the fallback). A
 * profile that was never indexed resolves nothing rather than failing.
 */
public class TraceCodeLinker {

  /** A JVM stack frame: {@code at pkg.Class.method(Class.java:52)}. */
  private static final Pattern STACK_FRAME =
    Pattern.compile("\\bat\\s+([A-Za-z_][A-Za-z0-9_.$]*\\.[A-Za-z_][A-Za-z0-9_$]*)\\s*\\(");

  /** Gradle's failure line: {@code SomeTests > someCase FAILED}. */
  private static final Pattern GRADLE_TEST_FAILURE =
    Pattern.compile("([A-Za-z_][A-Za-z0-9_$]*)\\s*>\\s*[^\\n]*?\\bFAILED\\b");

  /** A source path; the file's base name is the symbol candidate. */
  private static final Pattern SOURCE_PATH =
    Pattern.compile("[\\w./-]*?([A-Za-z_][A-Za-z0-9_]*)\\.(?:java|kt|scala|js|ts|tsx|scss|py|go|rs)\\b");

  private final CodeIndexStore store;
  private final int maxLinkedSymbols;

  public TraceCodeLinker(CodeIndexStore store, int maxLinkedSymbols) {
    this.store = store;
    this.maxLinkedSymbols = Math.max(0, maxLinkedSymbols);
  }

  /** Symbol ids for the code this trace references, capped and deduped in resolution order. */
  public List<String> link(String profileId, TraceEvent event) {
    if (maxLinkedSymbols == 0) {
      return List.of();
    }
    String[] texts = {event.error(), event.output(), event.args()};

    List<String> qualified = extract(STACK_FRAME, texts);
    List<String> bare = new ArrayList<>(extract(GRADLE_TEST_FAILURE, texts));
    for (String name : extract(SOURCE_PATH, texts)) {
      if (!bare.contains(name)) {
        bare.add(name);
      }
    }
    if (qualified.isEmpty() && bare.isEmpty()) {
      return List.of();
    }

    Set<String> ids = new LinkedHashSet<>();
    if (!qualified.isEmpty()) {
      collect(ids, store.findSymbolsByQualifiedName(profileId, qualified, maxLinkedSymbols));
    }
    if (ids.size() < maxLinkedSymbols && !bare.isEmpty()) {
      collect(ids, store.findSymbolsByName(profileId, bare, maxLinkedSymbols));
    }
    return ids.stream().limit(maxLinkedSymbols).toList();
  }

  private static void collect(Set<String> ids, List<CodeSymbol> symbols) {
    if (symbols == null) {
      return;
    }
    for (CodeSymbol symbol : symbols) {
      if (symbol != null && symbol.id() != null && !symbol.id().isBlank()) {
        ids.add(symbol.id());
      }
    }
  }

  /** First capture group of every match across the given texts, deduped, in order. */
  private static List<String> extract(Pattern pattern, String... texts) {
    List<String> found = new ArrayList<>();
    for (String text : texts) {
      if (text == null || text.isBlank()) {
        continue;
      }
      Matcher matcher = pattern.matcher(text);
      while (matcher.find()) {
        String value = matcher.group(1);
        if (value != null && !value.isBlank() && !found.contains(value)) {
          found.add(value);
        }
      }
    }
    return found;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceCodeLinkerTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceCodeLinker.java \
        modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceCodeLinkerTests.java
git commit -m "feat(trace): resolve trace code references into payload.symbolIds"
```

---

### Task 9: Model-derived recipes

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceRecipe.java`
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceRecipeExtractor.java`
- Create: `modules/daemon/src/main/resources/prompts/extract-trace-recipes.txt`
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/model/ModelGateway.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceRecipeExtractorTests.java`

**Interfaces:**
- Consumes: `TraceEvent` (Task 4), `TraceProperties` (Task 4), `CommandSignature.of(...)` (Task 3), `GroundingFilter.grounded(String, String)`, `ModelGateway.verifyAll(List<String>, String)`, `VerificationResult` / `VerificationVerdict`.
- Produces:
  - `TraceRecipe(String command, String statement)`
  - `ModelGateway.extractTraceRecipes(String traceLog)` → `List<TraceRecipe>`, defaulting to `List.of()`
  - `new TraceRecipeExtractor(ModelGateway gateway, TraceProperties properties)`
  - `extract(List<TraceEvent> events, java.util.Set<String> knownSignatures)` → `TraceRecipeExtractor.Result(List<TraceRecipe> recipes, String traceLog, boolean skipped, int attempted, int dropped)`

> The extractor is the **only** place a model touches the trace path, and it runs at most once per ingest batch over the whole surviving sequence in order — that ordering is what lets a failure and its fix be visible together. The `extractTraceRecipes` default returning `List.of()` keeps every existing `ModelGateway` stub compiling unchanged, matching how `extractGraph` was added.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceRecipeExtractorTests.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.ingestion.model.VerificationResult;
import dev.alvo.pieria.ingestion.model.VerificationVerdict;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRecipeExtractorTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  /** Scripted gateway: no network, no Ollama. Records what it was asked. */
  private static final class ScriptedGateway implements ModelGateway {
    final List<String> recipeCalls = new ArrayList<>();
    final List<String> verifyCalls = new ArrayList<>();
    List<TraceRecipe> recipes = List.of();
    VerificationVerdict verdict = VerificationVerdict.PASS;

    @Override
    public List<TraceRecipe> extractTraceRecipes(String traceLog) {
      recipeCalls.add(traceLog);
      return recipes;
    }

    @Override
    public List<VerificationResult> verifyAll(List<String> contents, String transcript) {
      verifyCalls.addAll(contents);
      return contents.stream()
        .map(content -> new VerificationResult(verdict, content, "scripted"))
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

  private static TraceEvent event(String args, TraceStatus status, String error) {
    return new TraceEvent("id" + args + status, "s1", "Bash", args, "", status,
      status == TraceStatus.FAILURE ? 1 : 0, error, AT, false, 0);
  }

  // The cost guard: a batch of routine successes on commands already seen yields no new recipe,
  // so it must not pay for a model call at all.
  @Test
  void anAllSuccessBatchOfKnownCommandsSkipsTheModel() {
    ScriptedGateway gateway = new ScriptedGateway();
    TraceRecipeExtractor extractor =
      new TraceRecipeExtractor(gateway, TraceProperties.defaults());

    TraceRecipeExtractor.Result result = extractor.extract(
      List.of(event("./gradlew test", TraceStatus.SUCCESS, null)), Set.of("gradlew-test"));

    assertThat(result.skipped()).isTrue();
    assertThat(result.recipes()).isEmpty();
    assertThat(gateway.recipeCalls).isEmpty();
  }

  @Test
  void aFailureAlwaysEarnsAModelCall() {
    ScriptedGateway gateway = new ScriptedGateway();
    gateway.recipes = List.of(new TraceRecipe("./gradlew test", "Tests run with ./gradlew test."));

    TraceRecipeExtractor.Result result =
      new TraceRecipeExtractor(gateway, TraceProperties.defaults())
        .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")),
          Set.of("gradlew-test"));

    assertThat(result.skipped()).isFalse();
    assertThat(gateway.recipeCalls).hasSize(1);
    assertThat(result.recipes()).hasSize(1);
  }

  @Test
  void anUnseenCommandEarnsAModelCallEvenWhenItSucceeded() {
    ScriptedGateway gateway = new ScriptedGateway();
    gateway.recipes = List.of(new TraceRecipe("npm test", "Front-end tests run with npm test."));

    TraceRecipeExtractor.Result result =
      new TraceRecipeExtractor(gateway, TraceProperties.defaults())
        .extract(List.of(event("npm test", TraceStatus.SUCCESS, null)), Set.of("gradlew-test"));

    assertThat(result.skipped()).isFalse();
    assertThat(gateway.recipeCalls).hasSize(1);
  }

  // Exactly one call for the whole batch, and the log must preserve arrival order so a
  // failure and the fix that followed it are visible together.
  @Test
  void oneCallCarriesTheWholeBatchInOrder() {
    ScriptedGateway gateway = new ScriptedGateway();

    new TraceRecipeExtractor(gateway, TraceProperties.defaults()).extract(
      List.of(event("./gradlew test", TraceStatus.FAILURE, "boom"),
        event("./gradlew test", TraceStatus.SUCCESS, null)),
      Set.of());

    assertThat(gateway.recipeCalls).hasSize(1);
    String log = gateway.recipeCalls.getFirst();
    assertThat(log.indexOf("failure")).isLessThan(log.indexOf("success"));
  }

  // A statement plainly grounded in the log skips the model verifier, exactly as the
  // conversational path does.
  @Test
  void aGroundedStatementSkipsVerification() {
    ScriptedGateway gateway = new ScriptedGateway();
    gateway.recipes = List.of(
      new TraceRecipe("./gradlew test", "./gradlew test failed with exit 1 and boom"));

    TraceRecipeExtractor.Result result =
      new TraceRecipeExtractor(gateway, TraceProperties.defaults())
        .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")), Set.of());

    assertThat(gateway.verifyCalls).isEmpty();
    assertThat(result.recipes()).hasSize(1);
  }

  @Test
  void anUngroundedStatementIsVerifiedAndDroppedOnADropVerdict() {
    ScriptedGateway gateway = new ScriptedGateway();
    gateway.verdict = VerificationVerdict.DROP;
    gateway.recipes = List.of(new TraceRecipe("./gradlew test",
      "Deployment to production requires an approval from the release manager."));

    TraceRecipeExtractor.Result result =
      new TraceRecipeExtractor(gateway, TraceProperties.defaults())
        .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")), Set.of());

    assertThat(gateway.verifyCalls).hasSize(1);
    assertThat(result.recipes()).isEmpty();
    assertThat(result.dropped()).isEqualTo(1);
  }

  @Test
  void aCorrectVerdictReplacesTheStatement() {
    ScriptedGateway gateway = new ScriptedGateway();
    gateway.verdict = VerificationVerdict.CORRECT;
    gateway.recipes = List.of(new TraceRecipe("./gradlew test", "Something loosely related here."));

    TraceRecipeExtractor.Result result =
      new TraceRecipeExtractor(gateway, TraceProperties.defaults())
        .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")), Set.of());

    assertThat(result.recipes()).hasSize(1);
    assertThat(result.recipes().getFirst().statement()).isEqualTo("Something loosely related here.");
  }

  @Test
  void recipesAreCappedPerBatch() {
    TraceProperties d = TraceProperties.defaults();
    TraceProperties capped = new TraceProperties(d.enabled(), d.maxOutputChars(), d.spoolMaxBytes(),
      d.spoolRetentionDays(), d.stopDrainThresholdBytes(), d.stopDrainThresholdEvents(),
      d.toolDenylist(), d.skipUnchangedOutcomes(), d.recipeExtractionEnabled(), 1,
      d.maxLinkedSymbols(), d.recallBoost());

    ScriptedGateway gateway = new ScriptedGateway();
    gateway.recipes = List.of(
      new TraceRecipe("./gradlew test", "./gradlew test failed with boom"),
      new TraceRecipe("./gradlew build", "./gradlew build failed with boom"));

    TraceRecipeExtractor.Result result = new TraceRecipeExtractor(gateway, capped)
      .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")), Set.of());

    assertThat(result.recipes()).hasSize(1);
  }

  @Test
  void extractionCanBeDisabled() {
    TraceProperties d = TraceProperties.defaults();
    TraceProperties off = new TraceProperties(d.enabled(), d.maxOutputChars(), d.spoolMaxBytes(),
      d.spoolRetentionDays(), d.stopDrainThresholdBytes(), d.stopDrainThresholdEvents(),
      d.toolDenylist(), d.skipUnchangedOutcomes(), false, d.maxRecipesPerBatch(),
      d.maxLinkedSymbols(), d.recallBoost());

    ScriptedGateway gateway = new ScriptedGateway();

    assertThat(new TraceRecipeExtractor(gateway, off)
      .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")), Set.of()).skipped())
      .isTrue();
    assertThat(gateway.recipeCalls).isEmpty();
  }

  // Recipe derivation is additive: a model failure must lose the recipes, never the events.
  @Test
  void aModelFailureYieldsNoRecipesRatherThanPropagating() {
    ModelGateway exploding = new ModelGateway() {
      @Override
      public List<TraceRecipe> extractTraceRecipes(String traceLog) {
        throw new IllegalStateException("provider down");
      }

      @Override
      public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
        return "";
      }

      @Override
      public float[] embed(String text) {
        return new float[0];
      }
    };

    TraceRecipeExtractor.Result result =
      new TraceRecipeExtractor(exploding, TraceProperties.defaults())
        .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")), Set.of());

    assertThat(result.recipes()).isEmpty();
  }

  @Test
  void anEmptyBatchSkips() {
    ScriptedGateway gateway = new ScriptedGateway();

    assertThat(new TraceRecipeExtractor(gateway, TraceProperties.defaults())
      .extract(List.of(), Set.of()).skipped()).isTrue();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceRecipeExtractorTests'`
Expected: FAIL — compilation error, `TraceRecipe` and `TraceRecipeExtractor` do not exist.

- [ ] **Step 3: Write TraceRecipe**

Create `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceRecipe.java`:

```java
package dev.alvo.pieria.ingestion.trace;

/**
 * One procedural statement the model derived from a trace sequence, together with the command it
 * is about.
 *
 * <p>The command matters as much as the statement: it is what the topic key is computed from, so a
 * changed recipe for the same command supersedes rather than accumulating. Asking the model for the
 * command alongside the prose is cheaper and far more reliable than trying to recover it from the
 * sentence afterwards.
 *
 * @param command   the invocation the statement is about, e.g. {@code ./gradlew test}
 * @param statement the durable declarative sentence
 */
public record TraceRecipe(String command, String statement) {
}
```

- [ ] **Step 4: Add extractTraceRecipes to ModelGateway**

In `modules/daemon/src/main/java/dev/alvo/pieria/model/ModelGateway.java`, add the import:

```java
import dev.alvo.pieria.ingestion.trace.TraceRecipe;
```

Add this method after `extractGraphAll`:

```java
  /**
   * Trace recipe extraction: from an ordered log of tool calls and their outcomes, derive durable
   * procedural statements — "tests here are run with X", "Y fails with Z; the fix is W". Runs on
   * the small/fast model.
   *
   * <p>This is the only stage of the trace path that consults a model. Outcome events are built
   * deterministically in Java, because a trace already states the command and the exit code and
   * there is nothing to infer.
   *
   * <p>Additive and degradable, like {@link #extractGraph}: the default returns an empty list so
   * stubs and gateways without trace support keep working, and callers must treat any failure as
   * "store the events without recipes".
   */
  default List<TraceRecipe> extractTraceRecipes(String traceLog) {
    return List.of();
  }
```

- [ ] **Step 5: Write the prompt template**

Create `modules/daemon/src/main/resources/prompts/extract-trace-recipes.txt`:

```
You are reading an ordered log of tool calls a coding agent ran in one working session, with the
outcome of each. Derive the durable procedural knowledge a future session would want to know.

Log:
{{traceLog}}

Emit a JSON array. Each element is an object with exactly two string fields:
  "command"   - the invocation the statement is about, copied verbatim from the log
  "statement" - one declarative sentence stating the durable knowledge

Rules:
- Only state what the log shows. Never infer a cause the log does not contain.
- Prefer knowledge that stays true: how something is run, what a failure means, what fixed it.
- Skip one-off results. "The build failed at 10:04" is not durable; "the build fails when the
  sqlite-vec extension is missing, and installing it fixes the failure" is.
- When the log shows a failure followed by a fix, state both in one sentence.
- Emit an empty array if the log contains nothing durable. An empty array is a correct answer.
- Emit only the JSON array, with no prose before or after it.
```

- [ ] **Step 6: Write TraceRecipeExtractor**

Create `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceRecipeExtractor.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.ingestion.GroundingFilter;
import dev.alvo.pieria.ingestion.model.VerificationResult;
import dev.alvo.pieria.ingestion.model.VerificationVerdict;
import dev.alvo.pieria.model.ModelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The one model-consulting stage of the trace path: generalizes reusable procedural knowledge from
 * a sequence of tool calls.
 *
 * <p>It runs at most once per ingest batch, over the whole surviving sequence <em>in arrival
 * order</em>. The ordering is the point — a failure and the fix that followed it are frequently in
 * different turns, and only a batch that spans them can produce "X fails with Y; the fix is Z".
 *
 * <p>Unlike the deterministic outcome events, these statements <em>are</em> generalizations, so they
 * go through the same grounding pre-filter and model verification the conversational path uses.
 */
public class TraceRecipeExtractor {

  private static final Logger log = LoggerFactory.getLogger(TraceRecipeExtractor.class);

  private final ModelGateway modelGateway;
  private final TraceProperties properties;

  public TraceRecipeExtractor(ModelGateway modelGateway, TraceProperties properties) {
    this.modelGateway = modelGateway;
    this.properties = properties;
  }

  /**
   * @param recipes   verified statements, capped at {@code maxRecipesPerBatch}
   * @param traceLog  the log the model saw, reused as the verification transcript
   * @param skipped   whether the cost guard or the config switch avoided the model call entirely
   * @param attempted how many candidates the model returned
   * @param dropped   how many verification rejected
   */
  public record Result(List<TraceRecipe> recipes, String traceLog, boolean skipped, int attempted,
                       int dropped) {

    static Result skipped() {
      return new Result(List.of(), "", true, 0, 0);
    }
  }

  /**
   * @param events           surviving traces, in arrival order
   * @param knownSignatures  command signatures this profile has already recorded an outcome for
   */
  public Result extract(List<TraceEvent> events, Set<String> knownSignatures) {
    if (!properties.recipeExtractionEnabled() || properties.maxRecipesPerBatch() == 0
      || events == null || events.isEmpty()) {
      return Result.skipped();
    }
    if (!worthAModelCall(events, knownSignatures)) {
      // A batch of routine successes on commands already recorded yields nothing new.
      return Result.skipped();
    }

    String traceLog = renderLog(events);
    List<TraceRecipe> candidates;
    try {
      candidates = modelGateway.extractTraceRecipes(traceLog);
    } catch (RuntimeException e) {
      // Additive and degradable: losing the recipes must never lose the events.
      log.warn("trace recipe extraction failed; storing events without recipes: {}", e.toString());
      return new Result(List.of(), traceLog, false, 0, 0);
    }
    if (candidates == null || candidates.isEmpty()) {
      return new Result(List.of(), traceLog, false, 0, 0);
    }

    return verify(candidates, traceLog);
  }

  /**
   * Whether this batch can plausibly teach anything: any failure, or any command this profile has
   * not recorded an outcome for. Everything else is a repeat of known-good behaviour.
   */
  private static boolean worthAModelCall(List<TraceEvent> events, Set<String> knownSignatures) {
    Set<String> known = knownSignatures == null ? Set.of() : knownSignatures;
    for (TraceEvent event : events) {
      if (event.status() == TraceStatus.FAILURE || !known.contains(event.signature())) {
        return true;
      }
    }
    return false;
  }

  /** The ordered log the model reads, and the transcript verification checks statements against. */
  private static String renderLog(List<TraceEvent> events) {
    StringBuilder log = new StringBuilder();
    int index = 1;
    for (TraceEvent event : events) {
      log.append(index++).append(". ").append(event.invocation())
        .append("\n   status: ").append(event.status().wire());
      if (event.exitCode() != null) {
        log.append(" (exit ").append(event.exitCode()).append(')');
      }
      log.append("\n   signal: ").append(event.signalLine()).append('\n');
    }
    return log.toString();
  }

  /**
   * Grounded statements pass without a model call; the rest go to batched verification, exactly as
   * the conversational path does.
   */
  private Result verify(List<TraceRecipe> candidates, String traceLog) {
    List<TraceRecipe> accepted = new ArrayList<>();
    List<TraceRecipe> suspects = new ArrayList<>();
    for (TraceRecipe candidate : candidates) {
      if (candidate == null || candidate.statement() == null || candidate.statement().isBlank()) {
        continue;
      }
      if (GroundingFilter.grounded(candidate.statement(), traceLog)) {
        accepted.add(candidate);
      } else {
        suspects.add(candidate);
      }
    }

    int dropped = 0;
    if (!suspects.isEmpty()) {
      List<String> contents = suspects.stream().map(TraceRecipe::statement).toList();
      List<VerificationResult> verdicts;
      try {
        verdicts = modelGateway.verifyAll(contents, traceLog);
      } catch (RuntimeException e) {
        log.warn("trace recipe verification failed; dropping {} suspect(s): {}",
          suspects.size(), e.toString());
        verdicts = List.of();
      }
      for (int i = 0; i < suspects.size(); i++) {
        VerificationResult verdict = i < verdicts.size() ? verdicts.get(i) : null;
        if (verdict == null || verdict.verdict() == VerificationVerdict.DROP) {
          dropped++;
          continue;
        }
        String content = verdict.verdict() == VerificationVerdict.CORRECT
          ? verdict.content() : suspects.get(i).statement();
        accepted.add(new TraceRecipe(suspects.get(i).command(), content));
      }
    }

    List<TraceRecipe> capped = accepted.size() <= properties.maxRecipesPerBatch()
      ? accepted : accepted.subList(0, properties.maxRecipesPerBatch());
    return new Result(List.copyOf(capped), traceLog, false, candidates.size(), dropped);
  }
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceRecipeExtractorTests'`
Expected: PASS

- [ ] **Step 8: Verify no existing gateway stub broke**

Run: `./gradlew :daemon:compileTestJava`
Expected: BUILD SUCCESSFUL. `extractTraceRecipes` has a default body, so `FakeModelGateway`, `StubModelGateway`, and every other implementation compile unchanged.

- [ ] **Step 9: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceRecipe.java \
        modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceRecipeExtractor.java \
        modules/daemon/src/main/java/dev/alvo/pieria/model/ModelGateway.java \
        modules/daemon/src/main/resources/prompts/extract-trace-recipes.txt \
        modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceRecipeExtractorTests.java
git commit -m "feat(trace): derive verified procedural recipes from a trace batch"
```

---

### Task 10: Trace ingestion service

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceIngestionService.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceIngestionServiceTests.java`

**Interfaces:**
- Consumes: everything from Tasks 3–9, plus `MemoryStore.getOrCreateProfile(String)`, `MemoryStore.insertMessages(String, String, List<Message>)`, `MemoryStore.store(String, Memory, GraphFragment)`, `MemoryStore.findActiveByTopicKey(String, MemoryType, String)`, `PieriaProperties` (for the graph caps), `CodeIndexStore`.
- Produces: `new TraceIngestionService(MemoryStore, CodeIndexStore, ModelGateway, TraceProperties, PieriaProperties)`; `ingest(String profileName, String sessionId, List<TraceEventDto> traces)` → `List<Memory>`.

> Pipeline order is **redact → filter → persist raw → events → recipes**. Persisting raw *after* filtering is deliberate: storing every successful `Read` would put pure noise into the `messages` table and therefore into `MessageFtsChannel`.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceIngestionServiceTests.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.storage.NoOpCodeIndexStore;
import dev.alvo.pieria.storage.SqliteMemoryStore;
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
    // Follow the construction used in SqliteMemoryStoreVectorTests for the on-disk store.
    this.store = newSqliteStore(tempDir.resolve("trace.db"));
    this.service = new TraceIngestionService(store, new NoOpCodeIndexStore(), new SilentGateway(),
      TraceProperties.defaults(), defaultPieriaProperties());
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
      new SilentGateway(), off, defaultPieriaProperties());

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
    assertThat(store.graphCounts(profileId).edges()).isPositive();

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
```

> **Implementer note:** `graphCounts`, `findEntitiesByName`, and `findMemoriesByEntities` are existing `MemoryStore` methods. If `GraphCounts` exposes edges under a different accessor than `edges()`, adjust — read `modules/daemon/src/main/java/dev/alvo/pieria/domain/graph/GraphCounts.java` first.

> **Implementer note:** `newSqliteStore(Path)` and `defaultPieriaProperties()` are helpers you must write in this test class. Copy the on-disk `SqliteMemoryStore` construction and the `PieriaProperties` literal from `modules/daemon/src/test/java/dev/alvo/pieria/storage/SqliteMemoryStoreVectorTests.java` (around line 142), which already builds both for a `@TempDir` database. Do not add a factory to production code for this.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceIngestionServiceTests'`
Expected: FAIL — compilation error, `TraceIngestionService` does not exist.

- [ ] **Step 3: Write TraceIngestionService**

Create `modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceIngestionService.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.storage.CodeIndexStore;
import dev.alvo.pieria.storage.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The write path for execution traces: deduplicate, redact, reject noise, then derive memories.
 *
 * <p>Deliberately <em>not</em> routed through {@code IngestionService}'s chunked extraction. A trace
 * is already structured, so chunking it into a transcript and asking a model to re-read it would pay
 * tokens to restate facts the payload already carries. What the two paths share is everything after
 * derivation: the same {@code MemoryStore.store} call, and therefore the same supersession,
 * graph persistence, and vectorization outbox.
 *
 * <p>Stage order is redact, filter, persist raw, derive events, derive recipes. Persisting raw rows
 * after the filter is deliberate: every successful {@code Read} stored as a {@code messages} row
 * would be pure noise in {@code MessageFtsChannel}.
 */
@Service
public class TraceIngestionService {

  private static final Logger log = LoggerFactory.getLogger(TraceIngestionService.class);

  /** Role used for the raw trace rows in {@code messages}. */
  private static final String TRACE_ROLE = "tool";

  private final MemoryStore store;
  private final TraceProperties properties;
  private final TraceRelevanceFilter relevanceFilter;
  private final TraceGraphBuilder graphBuilder;
  private final TraceCodeLinker codeLinker;
  private final TraceRecipeExtractor recipeExtractor;

  public TraceIngestionService(MemoryStore store,
                               CodeIndexStore codeIndexStore,
                               ModelGateway modelGateway,
                               TraceProperties properties,
                               PieriaProperties pieria) {
    this.store = store;
    this.properties = properties;
    this.relevanceFilter = new TraceRelevanceFilter(properties);
    this.graphBuilder = new TraceGraphBuilder(
      pieria.ingestion().maxGraphEntitiesPerMemory(), pieria.ingestion().maxGraphTriplesPerMemory());
    this.codeLinker = new TraceCodeLinker(codeIndexStore, properties.maxLinkedSymbols());
    this.recipeExtractor = new TraceRecipeExtractor(modelGateway, properties);
  }

  /** Ingest one batch of traces, returning the memories actually stored (empty when all deduped). */
  public List<Memory> ingest(String profileName, String sessionId, List<TraceEventDto> traces) {
    if (!properties.enabled() || traces == null || traces.isEmpty()) {
      return List.of();
    }
    String profileId = store.getOrCreateProfile(profileName).id();
    Instant receiptTime = Instant.now();
    Path repoRoot = Path.of("").toAbsolutePath();
    Path userHome = Path.of(System.getProperty("user.home", "")).toAbsolutePath();

    // 1. Redact and resolve times, deduping identical events inside the batch by content id.
    Map<String, TraceEvent> byId = new LinkedHashMap<>();
    int fromReceiptClock = 0;
    int redactionHits = 0;
    for (TraceEventDto dto : traces) {
      TraceEvent event = TraceEvent.from(
        profileId, sessionId, dto, properties.maxOutputChars(), repoRoot, userHome, receiptTime);
      byId.putIfAbsent(event.id(), event);
      fromReceiptClock += event.occurredAtFromReceipt() ? 1 : 0;
      redactionHits += event.redactionHits();
    }
    List<TraceEvent> deduped = List.copyOf(byId.values());

    // 2. Reject noise. The lookup gives the filter the active outcome for a signature without
    //    handing it a store it has no business owning.
    TraceRelevanceFilter.Result filtered =
      relevanceFilter.filter(deduped, signature -> activeOutcome(profileId, signature));
    if (filtered.kept().isEmpty()) {
      logSummary(traces.size(), deduped.size(), filtered, 0, 0, redactionHits, fromReceiptClock);
      return List.of();
    }

    // 3. Persist survivors as raw evidence. INSERT OR IGNORE over a content-addressed id, so
    //    re-shipping a spool inserts nothing.
    store.insertMessages(profileId, sessionId, filtered.kept().stream()
      .map(event -> new Message(null, sessionId, TRACE_ROLE,
        TraceMemoryFactory.rawMessageContent(event), event.occurredAt()))
      .toList());

    // 4. Deterministic outcome events, with their graph fragment and code links.
    List<Memory> stored = new ArrayList<>();
    Set<String> signatures = new LinkedHashSet<>();
    for (TraceEvent event : filtered.kept()) {
      signatures.add(event.signature());
      List<String> symbolIds = codeLinker.link(profileId, event);
      Memory memory = TraceMemoryFactory.outcome(event, symbolIds);
      GraphFragment graph = graphBuilder.build(event);
      MemoryStore.StoreOutcome outcome = store.store(profileId, memory, graph);
      if (outcome.inserted()) {
        stored.add(outcome.stored());
      }
    }

    // 5. One model pass over the batch for reusable recipes. Additive: a failure here loses the
    //    recipes, never the events already stored above.
    TraceRecipeExtractor.Result recipes = recipeExtractor.extract(filtered.kept(), knownSignatures(
      profileId, signatures));
    for (TraceRecipe recipe : recipes.recipes()) {
      Memory memory = TraceMemoryFactory.recipe(
        recipe.statement(),
        CommandSignature.of("Bash", recipe.command()),
        filtered.kept().getLast().occurredAt(),
        List.of());
      MemoryStore.StoreOutcome outcome = store.store(profileId, memory, GraphFragment.empty());
      if (outcome.inserted()) {
        stored.add(outcome.stored());
      }
    }

    logSummary(traces.size(), deduped.size(), filtered, stored.size(), recipes.dropped(),
      redactionHits, fromReceiptClock);
    return List.copyOf(stored);
  }

  private Optional<Memory> activeOutcome(String profileId, String signature) {
    List<Memory> active = store.findActiveByTopicKey(
      profileId, MemoryType.EVENT, TraceMemoryFactory.OUTCOME_KEY_PREFIX + signature);
    return active.isEmpty() ? Optional.empty() : Optional.of(active.getFirst());
  }

  /** Signatures this profile already records an outcome for; feeds the recipe cost guard. */
  private Set<String> knownSignatures(String profileId, Set<String> candidates) {
    Set<String> known = new LinkedHashSet<>();
    for (String signature : candidates) {
      if (activeOutcome(profileId, signature).isPresent()) {
        known.add(signature);
      }
    }
    return known;
  }

  /**
   * Per-stage counts, on-machine only. Redaction is reported as a <em>hit count</em>, never as
   * content — logging what was redacted would defeat redacting it.
   */
  private void logSummary(int received, int deduped, TraceRelevanceFilter.Result filtered,
                          int storedCount, int recipesDropped, int redactionHits,
                          int fromReceiptClock) {
    log.info("trace ingest: received={} deduped={} kept={} dropped={} stored={} "
        + "recipesDropped={} redactionHits={} receiptClockTimestamps={}",
      received, deduped, filtered.kept().size(), filtered.droppedByRule(), storedCount,
      recipesDropped, redactionHits, fromReceiptClock);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceIngestionServiceTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/ingestion/trace/TraceIngestionService.java \
        modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceIngestionServiceTests.java
git commit -m "feat(trace): add TraceIngestionService orchestrating the trace write path"
```

---

### Task 11: Route traces through POST /ingest

**Files:**
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/api/controller/ProfileController.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/api/ProfileTraceApiTests.java`

**Interfaces:**
- Consumes: `TraceIngestionService.ingest(String, String, List<TraceEventDto>)` (Task 10); `IngestRequest.traces()` (Task 1).
- Produces: no new public API beyond the endpoint accepting `traces`.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/api/ProfileTraceApiTests.java`. Mirror the `@WebMvcTest` setup at the top of `modules/daemon/src/test/java/dev/alvo/pieria/api/ProfileApiTests.java` (same `@WebMvcTest` controller list, same `@TestConfiguration` bean set), adding a `TraceIngestionService` bean:

```java
package dev.alvo.pieria.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProfileTraceApiTests {

  @Autowired
  MockMvc mockMvc;

  // The compatibility guarantee: the payload every existing caller sends must keep working.
  @Test
  void messagesOnlyIngestStillWorks() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"sessionId":"s1","messages":[{"role":"user","content":"hello"}]}"""))
      .andExpect(status().isOk());
  }

  @Test
  void tracesOnlyIngestIsAccepted() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"sessionId":"s1","traces":[
            {"tool":"Bash","args":"./gradlew test","status":"failure","exitCode":1,
             "output":"BUILD FAILED","endedAt":"2026-08-29T10:00:00Z"}]}"""))
      .andExpect(status().isOk());
  }

  @Test
  void mixedIngestIsAccepted() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"sessionId":"s1",
           "messages":[{"role":"user","content":"run the tests"}],
           "traces":[{"tool":"Bash","args":"./gradlew test","status":"success","exitCode":0}]}"""))
      .andExpect(status().isOk());
  }

  // The @AssertTrue guard that replaced @NotEmpty on messages.
  @Test
  void anIngestCarryingNeitherIsRejected() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"sessionId":"s1","messages":[],"traces":[]}"""))
      .andExpect(status().isBadRequest());
  }

  @Test
  void anUnknownTraceStatusDegradesRatherThanFailing() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"sessionId":"s1","traces":[
            {"tool":"Bash","args":"./gradlew test","status":"weird"}]}"""))
      .andExpect(status().isOk());
  }

  @Test
  void aTraceWithoutAToolIsRejected() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"sessionId":"s1","traces":[{"args":"./gradlew test","status":"success"}]}"""))
      .andExpect(status().isBadRequest());
  }
}
```

> **Implementer note:** `TraceStatus.fromWire` is lenient, but Jackson's default enum binding is not. Wire the DTO's `status` to the lenient parser so `"weird"` degrades to `UNKNOWN` instead of producing a 400 — add `@JsonCreator` on a static factory in `TraceStatus`, using `com.fasterxml.jackson.annotation.JsonCreator` (the annotations artifact `shared` already exports). If the test above shows a 400 for `"weird"`, that annotation is what is missing.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.api.ProfileTraceApiTests'`
Expected: FAIL — the traces-only case 400s, because `ProfileController.ingest` still calls `toMessages` unconditionally and never reads `traces`.

- [ ] **Step 3: Wire the controller**

In `modules/daemon/src/main/java/dev/alvo/pieria/api/controller/ProfileController.java`:

Add the import and constructor dependency:

```java
import dev.alvo.pieria.ingestion.trace.TraceIngestionService;
```

Add a `private final TraceIngestionService traceIngestionService;` field, a matching constructor parameter, and its assignment, following the existing field order.

Replace the `ingest` method body:

```java
  @PostMapping("/ingest")
  public IngestResponse ingest(@PathVariable String name,
                               @Valid @RequestBody IngestRequest request) {
    List<Memory> stored = new ArrayList<>();

    if (!request.messages().isEmpty()) {
      stored.addAll(ingestionService.ingest(
        name, request.sessionId(), toMessages(request), request.extractionSamples()));
    }
    // Traces run their own deterministic path; a request may carry either list, or both.
    if (!request.traces().isEmpty()) {
      stored.addAll(traceIngestionService.ingest(name, request.sessionId(), request.traces()));
    }

    return IngestResponse.of(stored.stream().map(this.memoryResponseConverter::convert).toList());
  }
```

Add `import java.util.ArrayList;` if it is not already present.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.api.ProfileTraceApiTests'`
Expected: PASS

- [ ] **Step 5: Run the full daemon suite**

Run: `./gradlew :daemon:test`
Expected: PASS. `ProfileApiTests` and every other `@WebMvcTest` will need the new `TraceIngestionService` bean in their `@TestConfiguration`; add it where the compiler or context startup reports it missing.

- [ ] **Step 6: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/api/controller/ProfileController.java \
        modules/daemon/src/test/java/dev/alvo/pieria/api/ \
        modules/shared/src/main/java/dev/alvo/pieria/api/request/TraceStatus.java
git commit -m "feat(trace): accept traces on POST /ingest alongside or instead of messages"
```

---

### Task 12: The trace spool

**Files:**
- Create: `modules/cli/src/main/java/dev/alvo/pieria/cli/modules/hook/TraceSpool.java`
- Test: `modules/cli/src/test/java/dev/alvo/pieria/cli/modules/hook/TraceSpoolTests.java`

**Interfaces:**
- Consumes: `TraceEventDto`, `TraceStatus` (Task 1); `AppDirs.defaultDataRoot()` from `dev.alvo.pieria.tools.os`.
- Produces: `new TraceSpool(Path root)`; `TraceSpool.defaultRoot()` → `Path`; `append(String sessionId, TraceEventDto event)` → `void`; `drain(String sessionId)` → `List<TraceEventDto>`; `sizeBytes(String sessionId)` → `long`; `eventCount(String sessionId)` → `int`; `sweepStale(int retentionDays)` → `int`.

> The spool lives under the **app-data root**, not `PIERIA_HOME` — see **Deviations From The Spec #1**. `InstallHome`'s javadoc says outright not to conflate the install root with app data, and a spool is runtime state.
>
> Appends take an explicit `FileChannel.lock()` rather than relying on `O_APPEND` atomicity: a redacted line can exceed `PIPE_BUF`, and a harness may run tool calls in parallel.

- [ ] **Step 1: Write the failing test**

Create `modules/cli/src/test/java/dev/alvo/pieria/cli/modules/hook/TraceSpoolTests.java`:

```java
package dev.alvo.pieria.cli.modules.hook;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TraceSpoolTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  @TempDir
  Path root;

  private static TraceEventDto event(String args) {
    return new TraceEventDto("Bash", args, "out", TraceStatus.SUCCESS, 0, null, AT, AT);
  }

  @Test
  void appendedEventsDrainInOrder() {
    TraceSpool spool = new TraceSpool(root);
    spool.append("s1", event("first"));
    spool.append("s1", event("second"));

    List<TraceEventDto> drained = spool.drain("s1");

    assertThat(drained).hasSize(2);
    assertThat(drained.get(0).args()).isEqualTo("first");
    assertThat(drained.get(1).args()).isEqualTo("second");
  }

  @Test
  void drainingEmptiesTheSpool() {
    TraceSpool spool = new TraceSpool(root);
    spool.append("s1", event("only"));

    spool.drain("s1");

    assertThat(spool.drain("s1")).isEmpty();
    assertThat(spool.sizeBytes("s1")).isZero();
  }

  @Test
  void drainingAnUnknownSessionIsHarmless() {
    assertThat(new TraceSpool(root).drain("never-existed")).isEmpty();
    assertThat(new TraceSpool(root).sizeBytes("never-existed")).isZero();
    assertThat(new TraceSpool(root).eventCount("never-existed")).isZero();
  }

  @Test
  void sessionsAreIsolated() {
    TraceSpool spool = new TraceSpool(root);
    spool.append("s1", event("one"));
    spool.append("s2", event("two"));

    assertThat(spool.drain("s1")).hasSize(1);
    assertThat(spool.drain("s2")).hasSize(1);
  }

  // A session id arrives from a harness and reaches a file name; it must not be able to escape
  // the spool directory.
  @Test
  void sessionIdsAreSanitizedIntoFileNames() throws IOException {
    TraceSpool spool = new TraceSpool(root);
    spool.append("../../etc/passwd", event("x"));

    try (var files = Files.walk(root)) {
      assertThat(files.filter(Files::isRegularFile))
        .allSatisfy(path -> assertThat(path.normalize()).startsWith(root.normalize()));
    }
    assertThat(spool.drain("../../etc/passwd")).hasSize(1);
  }

  @Test
  void sizeAndCountReportTheCurrentSpool() {
    TraceSpool spool = new TraceSpool(root);
    spool.append("s1", event("one"));
    spool.append("s1", event("two"));

    assertThat(spool.eventCount("s1")).isEqualTo(2);
    assertThat(spool.sizeBytes("s1")).isPositive();
  }

  // A malformed line must not lose the whole spool, matching how the transcript parsers already
  // treat an unparseable record.
  @Test
  void malformedLinesAreSkippedRatherThanFailingTheDrain() throws IOException {
    TraceSpool spool = new TraceSpool(root);
    spool.append("s1", event("good"));
    Files.writeString(root.resolve("s1.ndjson"),
      "\nthis is not json\n", java.nio.file.StandardOpenOption.APPEND);
    spool.append("s1", event("also-good"));

    assertThat(spool.drain("s1")).hasSize(2);
  }

  @Test
  void concurrentAppendsAllSurvive() throws Exception {
    TraceSpool spool = new TraceSpool(root);
    int writers = 8;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(writers);

    for (int i = 0; i < writers; i++) {
      int index = i;
      Thread.ofVirtual().start(() -> {
        try {
          start.await();
          spool.append("s1", event("writer-" + index));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      });
    }
    start.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

    assertThat(spool.drain("s1")).hasSize(writers);
  }

  // A runaway session must degrade, not fill the disk.
  @Test
  void exceedingTheSizeCapDropsTheOldestHalf() {
    TraceSpool spool = new TraceSpool(root, 2048);
    for (int i = 0; i < 200; i++) {
      spool.append("s1", event("event-" + i + "-" + "x".repeat(50)));
    }

    List<TraceEventDto> drained = spool.drain("s1");

    assertThat(drained).isNotEmpty();
    assertThat(drained.size()).isLessThan(200);
    // The tail is what survives: the newest events are the ones worth keeping.
    assertThat(drained.getLast().args()).contains("event-199");
  }

  @Test
  void staleSpoolsAreSweptAndFreshOnesKept() throws IOException {
    TraceSpool spool = new TraceSpool(root);
    spool.append("old", event("x"));
    spool.append("fresh", event("y"));
    Files.setLastModifiedTime(root.resolve("old.ndjson"),
      java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(30L * 86_400)));

    int swept = spool.sweepStale(7);

    assertThat(swept).isEqualTo(1);
    assertThat(spool.drain("fresh")).hasSize(1);
    assertThat(spool.drain("old")).isEmpty();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :cli:test --tests 'dev.alvo.pieria.cli.modules.hook.TraceSpoolTests'`
Expected: FAIL — compilation error, `TraceSpool` does not exist.

- [ ] **Step 3: Write TraceSpool**

Create `modules/cli/src/main/java/dev/alvo/pieria/cli/modules/hook/TraceSpool.java`:

```java
package dev.alvo.pieria.cli.modules.hook;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.tools.os.AppDirs;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A per-session, append-only NDJSON buffer of captured tool calls.
 *
 * <p>It exists because {@code PostToolUse} fires inside the agent's loop after every tool call.
 * Anything that touches the network there is paid dozens of times per turn; appending a line and
 * exiting is not. The turn-end hooks drain it and ship one batch, which also keeps a failure and
 * the fix that followed it inside a single extraction window.
 *
 * <p>Lives under the app-data root, not {@code PIERIA_HOME}: that is the install root, and
 * {@link AppDirs} exists precisely to keep the two apart.
 */
public final class TraceSpool {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String SUFFIX = ".ndjson";
  private static final long DEFAULT_MAX_BYTES = 4L * 1024 * 1024;

  private final Path root;
  private final long maxBytes;

  public TraceSpool(Path root) {
    this(root, DEFAULT_MAX_BYTES);
  }

  public TraceSpool(Path root, long maxBytes) {
    this.root = root;
    this.maxBytes = Math.max(4096L, maxBytes);
  }

  /** {@code <data-root>/spool/traces}. */
  public static Path defaultRoot() {
    return AppDirs.defaultDataRoot().resolve("spool").resolve("traces");
  }

  /**
   * Append one event. Takes an exclusive file lock rather than trusting {@code O_APPEND}: a
   * redacted line can exceed {@code PIPE_BUF}, and a harness may run tool calls in parallel.
   *
   * <p>Never throws on a spool problem — a hook that fails here would break the session it is
   * embedded in, and a lost trace is not worth that.
   */
  public void append(String sessionId, TraceEventDto event) {
    Path file = spoolFile(sessionId);
    try {
      Files.createDirectories(file.getParent());
      byte[] line = (MAPPER.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
      try (FileChannel channel = FileChannel.open(
        file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
           FileLock ignored = channel.lock()) {
        channel.write(java.nio.ByteBuffer.wrap(line));
      }
      if (Files.size(file) > maxBytes) {
        dropOldestHalf(file);
      }
    } catch (IOException | RuntimeException e) {
      // Deliberately swallowed: see the class javadoc.
    }
  }

  /** Read every parseable event and empty the spool. Unparseable lines are skipped, not fatal. */
  public List<TraceEventDto> drain(String sessionId) {
    Path file = spoolFile(sessionId);
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ,
      StandardOpenOption.WRITE);
         FileLock ignored = channel.lock()) {
      List<TraceEventDto> events = parse(readAll(channel));
      channel.truncate(0);
      return events;
    } catch (IOException | RuntimeException e) {
      return List.of();
    }
  }

  /** Current spool size in bytes; {@code 0} when there is no spool. */
  public long sizeBytes(String sessionId) {
    Path file = spoolFile(sessionId);
    try {
      return Files.isRegularFile(file) ? Files.size(file) : 0L;
    } catch (IOException e) {
      return 0L;
    }
  }

  /** Number of buffered lines; {@code 0} when there is no spool. */
  public int eventCount(String sessionId) {
    Path file = spoolFile(sessionId);
    if (!Files.isRegularFile(file)) {
      return 0;
    }
    try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
      return (int) lines.filter(line -> !line.isBlank()).count();
    } catch (IOException | RuntimeException e) {
      return 0;
    }
  }

  /** Delete spools older than {@code retentionDays}; returns how many were removed. */
  public int sweepStale(int retentionDays) {
    if (!Files.isDirectory(root)) {
      return 0;
    }
    Instant cutoff = Instant.now().minusSeconds(Math.max(1, retentionDays) * 86_400L);
    int swept = 0;
    try (Stream<Path> files = Files.list(root)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        FileTime modified = Files.getLastModifiedTime(file);
        if (modified.toInstant().isBefore(cutoff) && Files.deleteIfExists(file)) {
          swept++;
        }
      }
    } catch (IOException | RuntimeException e) {
      return swept;
    }
    return swept;
  }

  /**
   * Keep the newest half. The newest events are the ones worth shipping, and a session that
   * overruns the cap has already produced more than one batch can usefully carry.
   */
  private void dropOldestHalf(Path file) throws IOException {
    List<String> lines;
    try (Stream<String> stream = Files.lines(file, StandardCharsets.UTF_8)) {
      lines = stream.filter(line -> !line.isBlank()).toList();
    }
    List<String> kept = lines.subList(lines.size() / 2, lines.size());
    Files.writeString(file, String.join("\n", kept) + (kept.isEmpty() ? "" : "\n"),
      StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
  }

  private static String readAll(FileChannel channel) throws IOException {
    channel.position(0);
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) channel.size());
    while (buffer.hasRemaining() && channel.read(buffer) > 0) {
      // keep reading
    }
    return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
  }

  private static List<TraceEventDto> parse(String body) {
    List<TraceEventDto> events = new ArrayList<>();
    for (String line : body.split("\n")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty()) {
        continue;
      }
      try {
        events.add(MAPPER.readValue(trimmed, TraceEventDto.class));
      } catch (RuntimeException e) {
        // Skip an unparseable line rather than losing the batch, matching TranscriptParser.
      }
    }
    return List.copyOf(events);
  }

  /**
   * A session id arrives from a harness and ends up in a file name, so everything outside
   * {@code [a-z0-9._-]} is replaced. This is a containment rule, not cosmetics.
   */
  private Path spoolFile(String sessionId) {
    String raw = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
    String safe = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    if (safe.isBlank() || safe.chars().allMatch(c -> c == '.' || c == '-')) {
      safe = "default";
    }
    return root.resolve(safe + SUFFIX);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :cli:test --tests 'dev.alvo.pieria.cli.modules.hook.TraceSpoolTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/cli/src/main/java/dev/alvo/pieria/cli/modules/hook/TraceSpool.java \
        modules/cli/src/test/java/dev/alvo/pieria/cli/modules/hook/TraceSpoolTests.java
git commit -m "feat(trace): add locked per-session trace spool with growth cap and sweep"
```

---

### Task 13: The PostToolUse capture hook

**Files:**
- Modify: `modules/cli/src/main/java/dev/alvo/pieria/cli/modules/hook/HookInput.java`
- Create: `modules/cli/src/main/java/dev/alvo/pieria/cli/command/hook/CcPostToolUseCommand.java`
- Modify: `modules/cli/src/main/java/dev/alvo/pieria/cli/command/hook/ClaudeCodeHookCommand.java`
- Test: `modules/cli/src/test/java/dev/alvo/pieria/cli/modules/hook/HookInputTests.java` (extend)
- Test: `modules/cli/src/test/java/dev/alvo/pieria/cli/command/hook/CcPostToolUseCommandTests.java`

**Interfaces:**
- Consumes: `TraceSpool` (Task 12), `Redaction.scrub(...)` (Task 2), `TraceEventDto` / `TraceStatus` (Task 1).
- Produces: `HookInput.toolName()`, `HookInput.toolInput()`, `HookInput.toolResponse()`, `HookInput.exitCode()` (all nullable); `CcPostToolUseCommand` registered as `pieria hook claude-code post-tool-use`.

- [ ] **Step 1: Write the failing tests**

Add to `modules/cli/src/test/java/dev/alvo/pieria/cli/modules/hook/HookInputTests.java`:

```java
  @Test
  void postToolUseFieldsAreParsed() {
    HookInput input = HookInput.readLenient(new java.io.ByteArrayInputStream("""
      {"session_id":"s1","tool_name":"Bash",
       "tool_input":{"command":"./gradlew test"},
       "tool_response":{"stdout":"BUILD FAILED","exitCode":1}}
      """.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    assertThat(input.sessionId()).isEqualTo("s1");
    assertThat(input.toolName()).isEqualTo("Bash");
    assertThat(input.toolInput()).contains("./gradlew test");
    assertThat(input.toolResponse()).contains("BUILD FAILED");
    assertThat(input.exitCode()).isEqualTo(1);
  }

  // The lifecycle hooks must keep working: their payload has none of these fields.
  @Test
  void lifecyclePayloadsLeavePostToolUseFieldsUnset() {
    HookInput input = HookInput.readLenient(new java.io.ByteArrayInputStream("""
      {"session_id":"s1","transcript_path":"/tmp/t.jsonl"}
      """.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    assertThat(input.toolName()).isNull();
    assertThat(input.toolInput()).isNull();
    assertThat(input.exitCode()).isNull();
  }
```

Create `modules/cli/src/test/java/dev/alvo/pieria/cli/command/hook/CcPostToolUseCommandTests.java`:

```java
package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.PieriaCli;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class CcPostToolUseCommandTests {

  @Test
  void postToolUseIsRegistered() {
    CommandLine claudeCode = new CommandLine(new PieriaCli())
      .getSubcommands().get("hook").getSubcommands().get("claude-code");

    assertThat(claudeCode.getSubcommands().keySet()).contains("post-tool-use");
  }

  // The fail-closed contract: whatever stdin carries, the hook exits 0 and never breaks the
  // session it is embedded in.
  @Test
  void unusableStdinStillExitsZero() {
    for (String stdin : new String[] {"", "not-json", "[]", "{}"}) {
      assertThat(HookTestSupport.runWithStdin(stdin, "hook", "claude-code", "post-tool-use")
        .exitCode()).isZero();
    }
  }
}
```

> **Implementer note:** `HookCommandTests` already has a private `runWithStdin` helper and a `Run` record. Extract them into a package-private `HookTestSupport` class in the same test package and have both test classes use it, rather than duplicating. This is a test-only refactor; do not add anything to production code for it.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :cli:test --tests 'dev.alvo.pieria.cli.command.hook.CcPostToolUseCommandTests' --tests 'dev.alvo.pieria.cli.modules.hook.HookInputTests'`
Expected: FAIL — `CcPostToolUseCommand` does not exist; `HookInput` has no `toolName()`.

- [ ] **Step 3: Extend HookInput**

Replace the record declaration and `read` in `modules/cli/src/main/java/dev/alvo/pieria/cli/modules/hook/HookInput.java`:

```java
public record HookInput(String sessionId,
                        Path transcriptPath,
                        String toolName,
                        String toolInput,
                        String toolResponse,
                        Integer exitCode) {

  /** No payload: every field unset, so callers fall back to the environment. */
  public static final HookInput EMPTY = new HookInput(null, null, null, null, null, null);

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /**
   * Parse the hook payload, leaving absent or null fields unset.
   *
   * <p>{@code tool_name}/{@code tool_input}/{@code tool_response} are the PostToolUse fields; the
   * lifecycle hooks send none of them, and reading one reader for both keeps the two payload
   * shapes from needing separate parsers. {@code tool_input} and {@code tool_response} are kept as
   * raw JSON text because their shape varies per tool.
   */
  public static HookInput read(InputStream input) throws IOException {
    JsonNode root = MAPPER.readTree(input);
    if (root == null || !root.isObject()) {
      throw new IOException("hook input must be a JSON object");
    }
    String transcript = text(root, "transcript_path");
    return new HookInput(
      text(root, "session_id"),
      transcript == null ? null : Path.of(transcript),
      text(root, "tool_name"),
      raw(root, "tool_input"),
      raw(root, "tool_response"),
      exitCode(root.get("tool_response")));
  }
```

Keep `readLenient` and `text` unchanged, and add these helpers:

```java
  /** A child node as raw JSON text, or null when absent. */
  private static String raw(JsonNode root, String field) {
    JsonNode value = root.get(field);
    return value == null || value.isNull() ? null : value.toString();
  }

  /**
   * The tool response's exit code, under either spelling harnesses use. Absent for tools that do
   * not run a process, which is not an error.
   */
  private static Integer exitCode(JsonNode toolResponse) {
    if (toolResponse == null || !toolResponse.isObject()) {
      return null;
    }
    for (String field : new String[] {"exitCode", "exit_code", "returnCode"}) {
      JsonNode value = toolResponse.get(field);
      if (value != null && value.isNumber()) {
        return value.intValue();
      }
    }
    return null;
  }
```

- [ ] **Step 4: Write CcPostToolUseCommand**

Create `modules/cli/src/main/java/dev/alvo/pieria/cli/command/hook/CcPostToolUseCommand.java`:

```java
package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.cli.modules.hook.HookInput;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;
import dev.alvo.pieria.cli.modules.hook.TraceSpool;
import dev.alvo.pieria.tools.Redaction;
import picocli.CommandLine.Command;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Claude Code {@code PostToolUse}: record one tool call.
 *
 * <p>This runs after <em>every</em> tool call, inside the agent's loop, so it does exactly two
 * things — scrub and append a line — and never contacts the daemon. The turn-end hooks ship the
 * batch.
 */
@Command(name = "post-tool-use", description = "Claude Code PostToolUse hook.")
public final class CcPostToolUseCommand extends AbstractHookCommand {

  /**
   * Per-field cap applied here rather than daemon-side. The daemon re-applies its configured
   * budget, but the hook cannot read daemon config without a request it must not make, and an
   * uncapped write is what would make this hook slow.
   */
  private static final int CAPTURE_BUDGET_CHARS = 4000;

  @Override
  protected HookOutcome execute() {
    HookInput input = HookInput.readLenient(System.in);
    if (input.toolName() == null || input.toolName().isBlank()) {
      return new HookOutcome.Skipped("no tool_name in the PostToolUse payload; nothing to record");
    }

    Path repoRoot = Path.of("").toAbsolutePath();
    Path userHome = Path.of(System.getProperty("user.home", "")).toAbsolutePath();
    Instant now = Instant.now();

    TraceEventDto event = new TraceEventDto(
      input.toolName(),
      scrub(input.toolInput(), repoRoot, userHome),
      scrub(input.toolResponse(), repoRoot, userHome),
      status(input.exitCode()),
      input.exitCode(),
      null,
      null,
      now);

    new TraceSpool(TraceSpool.defaultRoot()).append(input.sessionId(), event);
    return HookOutcome.ok();
  }

  /**
   * Truncate first, then redact — that ordering bounds the work by the budget instead of by raw
   * output size, which is what keeps this off the critical path. A secret past the budget is
   * discarded rather than scanned, and never reaches disk either way.
   */
  private static String scrub(String text, Path repoRoot, Path userHome) {
    return text == null ? null
      : Redaction.scrub(text, CAPTURE_BUDGET_CHARS, repoRoot, userHome).text();
  }

  /** A missing exit code means the tool ran no process, which is not the same as succeeding. */
  private static TraceStatus status(Integer exitCode) {
    if (exitCode == null) {
      return TraceStatus.UNKNOWN;
    }
    return exitCode == 0 ? TraceStatus.SUCCESS : TraceStatus.FAILURE;
  }

  @Override
  protected String label() {
    return "pieria/claude-code-post-tool-use";
  }
}
```

- [ ] **Step 5: Register the subcommand**

In `modules/cli/src/main/java/dev/alvo/pieria/cli/command/hook/ClaudeCodeHookCommand.java`, add `CcPostToolUseCommand.class` to the `subcommands` list, after `CcSessionStartCommand.class`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :cli:test --tests 'dev.alvo.pieria.cli.command.hook.*' --tests 'dev.alvo.pieria.cli.modules.hook.HookInputTests'`
Expected: PASS. `HookInput.EMPTY` and both existing constructors changed arity, so fix any call site the compiler reports.

- [ ] **Step 7: Commit**

```bash
git add modules/cli/src/main/java/dev/alvo/pieria/cli/modules/hook/HookInput.java \
        modules/cli/src/main/java/dev/alvo/pieria/cli/command/hook/ \
        modules/cli/src/test/java/dev/alvo/pieria/cli/
git commit -m "feat(trace): capture tool calls in a PostToolUse hook without contacting the daemon"
```

---

### Task 14: Drain and ship

**Files:**
- Modify: `modules/shared/src/main/java/dev/alvo/pieria/client/ProfileClient.java`
- Modify: `modules/cli/src/main/java/dev/alvo/pieria/cli/command/hook/AbstractIngestHookCommand.java`
- Modify: `modules/cli/src/main/java/dev/alvo/pieria/cli/modules/harness/ClaudeCodeInstaller.java`
- Test: `modules/cli/src/test/java/dev/alvo/pieria/cli/modules/hook/TraceDrainPolicyTests.java`
- Test: `modules/cli/src/test/java/dev/alvo/pieria/cli/modules/harness/ClaudeCodeInstallerTests.java` (extend)

**Interfaces:**
- Consumes: `TraceSpool` (Task 12), `IngestRequest` (Task 1), `ProfileClient`.
- Produces: `ProfileClient.ingestTraces(String name, IngestRequest request, Duration timeout)` → `IngestResponse`; `TraceDrainPolicy.shouldDrain(boolean partial, long spoolBytes, int spoolEvents, long thresholdBytes, int thresholdEvents)` → `boolean`.

> The settled drain policy: `Stop` (`partial() == true`) drains only over threshold; `PreCompact` and `SessionEnd` (`partial() == false`) always drain. Draining on every `Stop` would shrink the batch to one turn, and a fail→fix pair routinely spans turns.

- [ ] **Step 1: Write the failing test**

Create `modules/cli/src/test/java/dev/alvo/pieria/cli/modules/hook/TraceDrainPolicyTests.java`:

```java
package dev.alvo.pieria.cli.modules.hook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceDrainPolicyTests {

  // A final capture must leave nothing behind, whatever the spool holds.
  @Test
  void aFinalCaptureAlwaysDrains() {
    assertThat(TraceDrainPolicy.shouldDrain(false, 0L, 0, 65_536L, 50)).isTrue();
    assertThat(TraceDrainPolicy.shouldDrain(false, 10L, 1, 65_536L, 50)).isTrue();
  }

  // The whole point: a small spool at the end of a turn keeps accumulating, so a failure in this
  // turn and its fix in the next land in one extraction window.
  @Test
  void anEndOfTurnCaptureUnderThresholdDoesNotDrain() {
    assertThat(TraceDrainPolicy.shouldDrain(true, 1_024L, 3, 65_536L, 50)).isFalse();
  }

  @Test
  void eitherThresholdAloneTriggersAnEndOfTurnDrain() {
    assertThat(TraceDrainPolicy.shouldDrain(true, 70_000L, 3, 65_536L, 50)).isTrue();
    assertThat(TraceDrainPolicy.shouldDrain(true, 1_024L, 60, 65_536L, 50)).isTrue();
  }

  @Test
  void anEmptySpoolNeverDrains() {
    assertThat(TraceDrainPolicy.shouldDrain(true, 0L, 0, 65_536L, 50)).isFalse();
    assertThat(TraceDrainPolicy.shouldDrain(false, 0L, 0, 65_536L, 50)).isTrue();
  }

  @Test
  void zeroThresholdsMakeEveryTurnDrain() {
    assertThat(TraceDrainPolicy.shouldDrain(true, 1L, 1, 0L, 0)).isTrue();
  }
}
```

Add to `modules/cli/src/test/java/dev/alvo/pieria/cli/modules/harness/ClaudeCodeInstallerTests.java`:

```java
  @Test
  void postToolUseHookIsInstalledAndRemoved() throws Exception {
    // Follow this test class's existing pattern for building a WiringContext and reading back
    // settings.json; assert the PostToolUse event is present after install and absent after
    // uninstall, exactly as the existing Stop/SessionEnd assertions do.
    assertThat(installedHookEvents()).contains("PostToolUse");
  }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :cli:test --tests 'dev.alvo.pieria.cli.modules.hook.TraceDrainPolicyTests'`
Expected: FAIL — compilation error, `TraceDrainPolicy` does not exist.

- [ ] **Step 3: Write TraceDrainPolicy**

Create `modules/cli/src/main/java/dev/alvo/pieria/cli/modules/hook/TraceDrainPolicy.java`:

```java
package dev.alvo.pieria.cli.modules.hook;

/**
 * When a lifecycle hook should ship the spool.
 *
 * <p>A final capture always drains — leaving traces behind at session end would lose them. An
 * end-of-turn capture drains only once the spool is large enough to be worth a round trip, because
 * {@code Stop} fires every turn and draining each time would cut every batch down to one turn. A
 * failure and the fix that followed it are routinely in different turns, and only a batch spanning
 * both can produce a useful recipe.
 */
public final class TraceDrainPolicy {

  private TraceDrainPolicy() {
  }

  /**
   * @param partial         whether this is a routine mid-session capture ({@code Stop})
   * @param spoolBytes      current spool size
   * @param spoolEvents     current buffered event count
   * @param thresholdBytes  size at or above which an end-of-turn capture drains
   * @param thresholdEvents count at or above which an end-of-turn capture drains
   */
  public static boolean shouldDrain(boolean partial, long spoolBytes, int spoolEvents,
                                    long thresholdBytes, int thresholdEvents) {
    if (!partial) {
      return true;
    }
    if (spoolEvents <= 0) {
      return false;
    }
    return spoolBytes >= thresholdBytes || spoolEvents >= thresholdEvents;
  }
}
```

- [ ] **Step 4: Add ingestTraces to ProfileClient**

In `modules/shared/src/main/java/dev/alvo/pieria/client/ProfileClient.java`, add after `ingestTranscriptAsync`:

```java
  /**
   * Ship captured tool calls to the same {@code /ingest} endpoint conversations use, with no
   * messages. The turn-end hooks POST raw NDJSON to {@code /ingest/transcript}, which cannot carry
   * a JSON field, so traces travel as their own request rather than riding along.
   */
  public IngestResponse ingestTraces(String name, IngestRequest request, Duration timeout) {
    return transport.parse(
      transport.post(profile(name) + "/ingest", request, timeout), IngestResponse.class);
  }
```

Add `import dev.alvo.pieria.api.request.IngestRequest;` if it is not already present.

- [ ] **Step 5: Drain in AbstractIngestHookCommand**

In `modules/cli/src/main/java/dev/alvo/pieria/cli/command/hook/AbstractIngestHookCommand.java`, add imports for `IngestRequest`, `TraceEventDto`, `TraceSpool`, `TraceDrainPolicy`, `java.time.Duration`, and `java.util.List`.

Insert this call at the start of `execute()`, before the transcript is resolved, and add the helper:

```java
  /** Spool thresholds, mirroring the daemon defaults; the hook cannot read daemon config. */
  private static final long STOP_DRAIN_THRESHOLD_BYTES = 65_536L;
  private static final int STOP_DRAIN_THRESHOLD_EVENTS = 50;
  private static final int SPOOL_RETENTION_DAYS = 7;
  private static final Duration TRACE_INGEST_TIMEOUT = Duration.ofSeconds(15);

  /**
   * Ship the spooled tool calls, if policy says to. Best-effort throughout: a trace failure must
   * never stop the transcript ingest that follows it, which is the capture that matters most.
   */
  private void shipTraces(HookContext ctx, String sessionId) {
    try {
      TraceSpool spool = new TraceSpool(TraceSpool.defaultRoot());
      spool.sweepStale(SPOOL_RETENTION_DAYS);
      if (!TraceDrainPolicy.shouldDrain(partial(), spool.sizeBytes(sessionId),
        spool.eventCount(sessionId), STOP_DRAIN_THRESHOLD_BYTES, STOP_DRAIN_THRESHOLD_EVENTS)) {
        return;
      }
      List<TraceEventDto> traces = spool.drain(sessionId);
      if (traces.isEmpty()) {
        return;
      }
      ctx.profiles().ingestTraces(ctx.profile(),
        new IngestRequest(sessionId, null, null, null, traces), TRACE_INGEST_TIMEOUT);
    } catch (RuntimeException e) {
      log.error("[{}] trace ingest failed: {}", label(), String.valueOf(e.getMessage()));
    }
  }
```

Then in `execute()`, after `sessionId` is resolved and before the `TranscriptIngestor.ingestFile` call, insert `shipTraces(ctx, sessionId);`.

> Move the `sessionId` resolution above the transcript-null check so traces still ship when a transcript is missing — a session with tool calls but no readable transcript should not lose both.

- [ ] **Step 6: Register PostToolUse in the installer**

In `modules/cli/src/main/java/dev/alvo/pieria/cli/modules/harness/ClaudeCodeInstaller.java`, add to `HOOK_EVENTS`:

```java
    put("PostToolUse", "post-tool-use");
```

Place it after `SessionStart`. Uninstall needs no change: it iterates `HOOK_EVENTS.keySet()`.

Update the class javadoc's hook list to name `PostToolUse`.

- [ ] **Step 7: Run the CLI and shared suites**

Run: `./gradlew :shared:test :cli:test`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add modules/shared/src/main/java/dev/alvo/pieria/client/ProfileClient.java \
        modules/cli/src/main/java/dev/alvo/pieria/cli/ \
        modules/cli/src/test/java/dev/alvo/pieria/cli/
git commit -m "feat(trace): drain the spool from turn-end hooks and register PostToolUse"
```

---

### Task 15: Recall boost and documentation

**Files:**
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/ReciprocalRankFusion.java`
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/RetrievalService.java:130`
- Modify: `docs/POTENTIAL_FEATURES.md`
- Modify: `docs/phases/phase-12-execution-trace-memory.md`
- Modify: `AGENTS.md`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/retrieval/TraceRecallBoostTests.java`

**Interfaces:**
- Consumes: `TraceProperties.recallBoost()` (Task 4), `TraceMemoryFactory.SOURCE_TRACE` (Task 6).
- Produces: `new ReciprocalRankFusion(int k, Map<RetrievalChannelType, Double> weights, double traceBoost)`, with the existing two-argument constructor retained and defaulting the boost to `1.0`.

> No new channel. Trace memories already ride `MemoryFts`, `ExactKey`, `DirectVector`, `Hyde`, `Graph`, and — through `payload.symbolIds` — `CodeGraph`, plus `MessageFts` via their raw `role="tool"` rows. This task only adds the tuning knob.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/retrieval/TraceRecallBoostTests.java`:

```java
package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRecallBoostTests {

  private static Memory memory(String id, String content, String payload) {
    return new Memory(id, "s1", MemoryType.EVENT, content, null, null, false, payload, content, null);
  }

  private static final Memory TRACE =
    memory("t1", "`./gradlew test` succeeded (exit 0)", "{\"source\":\"trace\"}");
  private static final Memory CHAT = memory("c1", "We should run the tests.", "{}");

  private static Map<RetrievalChannelType, Double> weights() {
    return Map.of(RetrievalChannelType.FTS_MEMORY, 1.0);
  }

  // Default 1.0 must leave ranking exactly as it is today.
  @Test
  void aBoostOfOneChangesNothing() {
    List<RetrievalCandidate> hits = List.of(
      new RetrievalCandidate(CHAT, RetrievalChannelType.FTS_MEMORY, 1, null),
      new RetrievalCandidate(TRACE, RetrievalChannelType.FTS_MEMORY, 2, null));

    List<RecallCandidate> unboosted = new ReciprocalRankFusion(60, weights()).fuse(hits);
    List<RecallCandidate> explicit = new ReciprocalRankFusion(60, weights(), 1.0).fuse(hits);

    assertThat(explicit.stream().map(c -> c.memory().id()).toList())
      .isEqualTo(unboosted.stream().map(c -> c.memory().id()).toList());
  }

  @Test
  void aBoostAboveOneLiftsTraceCandidates() {
    List<RetrievalCandidate> hits = List.of(
      new RetrievalCandidate(CHAT, RetrievalChannelType.FTS_MEMORY, 1, null),
      new RetrievalCandidate(TRACE, RetrievalChannelType.FTS_MEMORY, 2, null));

    List<RecallCandidate> boosted = new ReciprocalRankFusion(60, weights(), 3.0).fuse(hits);

    assertThat(boosted.getFirst().memory().id()).isEqualTo("t1");
  }

  @Test
  void nonTraceCandidatesAreNeverBoosted() {
    List<RecallCandidate> boosted = new ReciprocalRankFusion(60, weights(), 3.0)
      .fuse(List.of(new RetrievalCandidate(CHAT, RetrievalChannelType.FTS_MEMORY, 1, null)));
    List<RecallCandidate> plain = new ReciprocalRankFusion(60, weights(), 1.0)
      .fuse(List.of(new RetrievalCandidate(CHAT, RetrievalChannelType.FTS_MEMORY, 1, null)));

    assertThat(boosted.getFirst().score()).isEqualTo(plain.getFirst().score());
  }

  @Test
  void aMalformedPayloadIsTreatedAsNonTrace() {
    Memory broken = memory("b1", "x", "not json at all");

    List<RecallCandidate> boosted = new ReciprocalRankFusion(60, weights(), 3.0)
      .fuse(List.of(new RetrievalCandidate(broken, RetrievalChannelType.FTS_MEMORY, 1, null)));
    List<RecallCandidate> plain = new ReciprocalRankFusion(60, weights(), 1.0)
      .fuse(List.of(new RetrievalCandidate(broken, RetrievalChannelType.FTS_MEMORY, 1, null)));

    assertThat(boosted.getFirst().score()).isEqualTo(plain.getFirst().score());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.retrieval.TraceRecallBoostTests'`
Expected: FAIL — no three-argument `ReciprocalRankFusion` constructor.

- [ ] **Step 3: Add the boost to ReciprocalRankFusion**

In `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/ReciprocalRankFusion.java`:

Add a `private final double traceBoost;` field and this constructor pair, replacing the existing single constructor:

```java
  /** Existing two-argument form: no trace boost. */
  public ReciprocalRankFusion(int k, Map<RetrievalChannelType, Double> weights) {
    this(k, weights, 1.0);
  }

  /**
   * @param traceBoost multiplier applied to candidates whose payload carries
   *                   {@code "source":"trace"}; {@code 1.0} disables it. Exists so procedural
   *                   recall can be tuned against the eval harness without a code change, and so
   *                   the default provably changes nothing.
   */
  public ReciprocalRankFusion(int k, Map<RetrievalChannelType, Double> weights, double traceBoost) {
    if (k < 1) {
      throw new IllegalArgumentException("RRF k must be >= 1, was " + k);
    }
    this.k = k;
    this.weights = getWeights(weights);
    this.traceBoost = traceBoost <= 0 ? 1.0 : traceBoost;
  }
```

In `fuse`, apply the multiplier where the score is computed:

```java
    for (FusionAccumulator acc : byMemory.values()) {
      double score = acc.score(k, weights) * boostFor(acc.memory);
      fused.add(new RecallCandidate(acc.memory, score, acc.source(k, weights)));
    }
```

And add the predicate:

```java
  /**
   * Whether this memory came from an execution trace. Matched on the payload marker rather than on
   * the memory type, because traces land on {@code event} and {@code instruction} alongside
   * conversational memories. A payload that is not JSON simply does not match.
   */
  private double boostFor(Memory memory) {
    if (traceBoost == 1.0 || memory == null || memory.payload() == null) {
      return 1.0;
    }
    return memory.payload().contains("\"source\":\"trace\"") ? traceBoost : 1.0;
  }
```

Add `import dev.alvo.pieria.domain.memory.Memory;` if it is not already present.

- [ ] **Step 4: Pass the configured boost at the call site**

In `modules/daemon/src/main/java/dev/alvo/pieria/retrieval/RetrievalService.java:130`, add a `TraceProperties` constructor dependency to `RetrievalService` and change the construction to:

```java
    ReciprocalRankFusion fusion =
      new ReciprocalRankFusion(cfg.rrfK(), getWeightsForRetrievalChannels(cfg),
        traceProperties.recallBoost());
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.retrieval.*'`
Expected: PASS. Every `RetrievalService` construction site in tests needs the new argument; pass `TraceProperties.defaults()`.

- [ ] **Step 6: Prove trace memories are actually reachable end to end**

The spec requires that the two motivating questions retrieve. This is the assertion that the
whole feature exists for, and none of the unit tests above make it.

Create `modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/TraceRecallReachabilityTests.java`:

```java
package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
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

class TraceRecallReachabilityTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  @TempDir
  Path tempDir;

  private MemoryStore store;
  private String profileId;

  /** Returns one fixed recipe so the instruction half is exercised without a live model. */
  private static final class RecipeGateway implements ModelGateway {
    @Override
    public List<TraceRecipe> extractTraceRecipes(String traceLog) {
      return List.of(new TraceRecipe("./gradlew test",
        "Tests in this repo are run with ./gradlew test."));
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
    // Reuse the helpers written for TraceIngestionServiceTests.
    this.store = newSqliteStore(tempDir.resolve("recall.db"));
    TraceIngestionService service = new TraceIngestionService(store, new NoOpCodeIndexStore(),
      new RecipeGateway(), TraceProperties.defaults(), defaultPieriaProperties());

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
```

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.ingestion.trace.TraceRecallReachabilityTests'`
Expected: PASS

- [ ] **Step 7: Update the documentation**

In `docs/POTENTIAL_FEATURES.md`, change item 6's status line and append a `Shipped:` line matching the format items 1, 2, and 14 already use:

```markdown
Phase: 12 | Status: done
```

```markdown
Shipped: `ingestion.trace` (`TraceEvent`/`CommandSignature`/`TraceRelevanceFilter`/`TraceMemoryFactory`/`TraceGraphBuilder`/`TraceCodeLinker`/`TraceRecipeExtractor`/`TraceIngestionService`) behind an optional `traces` list on `POST /v1/profiles/{name}/ingest`; captured by `pieria hook claude-code post-tool-use` into a local spool that the turn-end hooks drain. Outcome `event`s are derived deterministically in Java and keyed `trace:outcome:<signature>` so the latest supersedes; procedural `instruction`s are model-derived once per batch and keyed `trace:recipe:<signature>`. Traces ride the existing channels — no new channel — and reach the code channels through `payload.symbolIds`. Codex/OpenCode capture is not implemented: each needs a per-tool hook event that has not been verified to exist.
```

In `docs/phases/phase-12-execution-trace-memory.md`, add a status note directly under the title:

```markdown
> **Status: implemented (2026-08-29).** The design that was actually built is
> `docs/superpowers/specs/2026-08-26-execution-trace-memory-design.md`, which supersedes the
> Implementation Sequence below where the two disagree — this document predates the graph layer,
> the code index, and the CLI hook subsystem.
```

In `AGENTS.md`, add a bullet to the **Key design constraints** section:

```markdown
- **Traces derive facts in Java, recipes with a model**: an execution trace already states the command, the exit code, and the error, so outcome `event` memories are built deterministically and skip verification — they are grounded by construction. The small model runs once per batch, over the whole surviving sequence *in order*, only to generalize reusable `instruction` recipes; the ordering is what makes a failure and the fix that followed it visible together. Outcomes are keyed `trace:outcome:<command-signature>` so the newest supersedes rather than accumulating, which is also why trace memories must stamp `stated_at`: supersession orders on `MemoryTimes.knowledgeTime`, and a spool drained hours later would otherwise order by ingest time.
```

- [ ] **Step 8: Run the whole suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/retrieval/ \
        modules/daemon/src/test/java/dev/alvo/pieria/retrieval/ \
        modules/daemon/src/test/java/dev/alvo/pieria/ingestion/trace/ \
        docs/POTENTIAL_FEATURES.md docs/phases/phase-12-execution-trace-memory.md AGENTS.md
git commit -m "feat(trace): add configurable trace recall boost and document the phase"
```

---

## Verification

After every task is complete:

- [ ] `./gradlew test` — the whole suite passes.
- [ ] `./gradlew compileJava compileTestJava` — no module broke.
- [ ] Confirm the compatibility guarantee by hand: a `messages`-only `POST /ingest` body identical to one used before this work still returns 200 with memories.

Do **not** run `nativeCompile`, `nativeDist`, or `deployLocal`. Ask the user to run them.
