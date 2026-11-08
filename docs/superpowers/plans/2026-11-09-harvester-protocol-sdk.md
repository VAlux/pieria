# Harvesters Phase 2: Protocol, SDK and Conformance Kit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define harvester protocol v1 and give plugin authors a Java SDK plus a conformance kit that can test a harvester written in any language.

**Architecture:** A new standalone `modules/harvester-sdk` holds the wire records, a codec, a bounded line reader, the runner that hosts a Java harvester, the manifest parser and the conformance kit. The codec uses Jackson's tree model only, so neither the daemon nor third-party native builds need reflection hints. Nothing is wired into the daemon yet; phase 3 consumes this module.

**Tech Stack:** Java 25, Jackson 3 (`tools.jackson` databind tree model and `jackson-dataformat-toml`), JUnit 5, AssertJ.

**Spec:** `docs/phases/phase-16-knowledge-harvesters.md`, sections "Protocol v1" and "Developer experience".

## Global Constraints

- `./gradlew test` must pass before every commit. Never run `nativeCompile`, `nativeDist` or `deployLocal`.
- Work on branch `harvesters-phase-2`, never on `main`.
- `:harvester-sdk` must not depend on `:shared` or any other Pieria module, because outside developers consume it. Its only runtime dependencies are `tools.jackson.core:jackson-databind` and `tools.jackson.dataformat:jackson-dataformat-toml`.
- No Jackson data binding or reflection on SDK types: encode and decode through `JsonNode` only.
- Commands: `<exe> check` and `<exe> harvest`. Stdin is one JSON envelope line `{protocol, config, secrets, cursor, limits}`, then EOF. Stdout is JSON lines, each ending in `\n`.
- Protocol version: `1`. Wire field names are camelCase, exactly as in the spec.
- Exit codes: `0` success, `1` harvester failure, `2` usage or bad envelope.
- Unknown record `type` is a violation. Unknown fields on a known record are ignored.
- Size limits (`ProtocolLimits`): `MAX_LINE_BYTES = 16 * 1024 * 1024`, `MAX_ENVELOPE_BYTES = 1024 * 1024`, `MAX_TEXT_CHARS = 2_000_000` (a document's text, or the sum across a thread's turns), `MAX_ID_CHARS = 512`, `MAX_TITLE_CHARS = 1_000`, `MAX_URL_CHARS = 2_048`, `MAX_AUTHOR_CHARS = 256`, `MAX_TURNS = 5_000`, `MAX_SEEN_IDS = 1_000`, `MAX_CURSOR_CHARS = 65_536`, `MAX_METADATA_ENTRIES = 32`, `MAX_METADATA_VALUE_CHARS = 1_024`, `MAX_ERROR_CHARS = 2_000`.
- Manifest and source ids match `^[a-z0-9][a-z0-9_-]{0,63}$`.
- Test classes use the `*Tests` suffix. `Optional` is only a return type at a lookup boundary, per `.claude/rules/java-optional-usage.md`.

## Review Focus

1. A harvester dies part-way through a line: the reader reports a truncated stream and never parses half a record. Pinned in Task 1.
2. A harvester writes endless output with no newline: the reader stops at the limit instead of buffering it all. Pinned in Task 1.
3. Secrets reach logs through `toString`: `HarvestRequest` and `SecretUpdate` never print secret values. Pinned in Task 1.
4. Harvester code or a library prints to `System.out`: protocol output stays valid, because the printed text goes to stderr. Pinned in Task 4 with the `noisy` fixture.
5. The reader goes away mid-run: the harvester stops promptly instead of harvesting into a closed pipe. Pinned in Task 2 and in Task 4 with the `ignores-closed-stdout` fixture.

---

### Task 1: Module, wire records, codec and bounded reader

**Files:**
- Modify: `settings.gradle.kts` (add `harvester-sdk` to both module lists)
- Create: `modules/harvester-sdk/build.gradle.kts`
- Create under `modules/harvester-sdk/src/main/java/dev/alvo/pieria/harvester/protocol/`: `HarvestRecord.java`, `HarvestRequest.java`, `ErrorClass.java`, `DocumentFormat.java`, `ProtocolLimits.java`, `ProtocolViolation.java`, `RecordCodec.java`, `RequestCodec.java`, `BoundedLineReader.java`
- Test: `modules/harvester-sdk/src/test/java/dev/alvo/pieria/harvester/protocol/RecordCodecTests.java`, `RequestCodecTests.java`, `BoundedLineReaderTests.java`

**Interfaces:**
- Produces, in `dev.alvo.pieria.harvester.protocol`:
  - `sealed interface HarvestRecord` with nested records, each validating in its compact constructor and throwing `ProtocolViolation`:
    - `sealed interface Item extends HarvestRecord { String id(); }`
    - `Document(String id, String version, String title, String url, String author, Instant updatedAt, DocumentFormat format, String text, Map<String,String> metadata) implements Item`, wire `document`
    - `ConversationThread(String id, String title, String url, List<Turn> turns, Map<String,String> metadata) implements Item`, wire `thread`
    - `Turn(String id, String author, Instant timestamp, String text)`
    - `Tombstone(String id)`, `Seen(List<String> ids)`, `Sweep()`, `Checkpoint(JsonNode cursor)`, `Progress(long done, Long total)`, `SecretUpdate(String name, String value)`, `Failure(ErrorClass errorClass, String message, Long retryAfterSeconds)`, wire `tombstone`, `seen`, `sweep`, `checkpoint`, `progress`, `secret_update`, `error`
  - `enum ErrorClass { AUTH, RATE_LIMITED, TRANSIENT, CONFIG, FATAL }` with `String wire()` (`auth`, `rate_limited`, `transient`, `config`, `fatal`) and `static ErrorClass fromWire(String)`
  - `enum DocumentFormat { MARKDOWN, TEXT }`, wire `markdown` and `text`
  - `record HarvestRequest(int protocol, ObjectNode config, Map<String,String> secrets, JsonNode cursor, Limits limits)` with nested `record Limits(Integer maxItemsPerRun, Long maxCharsPerRun)` and `static final int PROTOCOL_VERSION = 1`. `cursor` is null on a first run. `limits` is never null, and its fields may be.
  - `final class ProtocolViolation extends RuntimeException`
  - `final class RecordCodec`: `static String encode(HarvestRecord)` (one line, no newline; throws `ProtocolViolation` over `MAX_LINE_BYTES`) and `static HarvestRecord decode(String line)`
  - `final class RequestCodec`: `static String encode(HarvestRequest)` and `static HarvestRequest decode(String line)`
  - `final class BoundedLineReader implements Closeable`: `BoundedLineReader(InputStream in, int maxLineBytes)`, `String readLine()` (null at clean EOF, throws `ProtocolViolation` on an oversize line, invalid UTF-8 or a final line with no newline; strips one trailing `\r`)

**Validation rules:** `id`, `url`, turn `id` and turn `author` are non-blank. `title` is non-null and may be blank. `text` is non-null and may be blank. `updatedAt` and `timestamp` are required. A thread has 1 to `MAX_TURNS` turns with unique ids. `Seen.ids` holds 1 to `MAX_SEEN_IDS` non-blank ids. `Checkpoint.cursor` is non-null and not JSON null, and serializes within `MAX_CURSOR_CHARS`. `Progress.done >= 0`, and `total` is null or `>= 0`. `SecretUpdate.name` and `value` are non-blank. `Failure.message` is truncated to `MAX_ERROR_CHARS` rather than rejected. `Failure.retryAfterSeconds` is null or `>= 0`. Timestamps accept any ISO-8601 instant with an offset via `Instant.parse`.

- [ ] **Step 1: Create the branch and write the failing tests**

Run: `git switch -c harvesters-phase-2`

```java
// RecordCodecTests
private static final Instant T = Instant.parse("2026-01-02T03:04:05Z");
private static final HarvestRecord.Document DOC = new HarvestRecord.Document("a", "v1", "Title", "https://x/a", "Ann", T,
  DocumentFormat.MARKDOWN, "# Hi\nline two", Map.of("k", "v"));

@Test void everyRecordRoundTrips() {
  for (HarvestRecord r : List.of(DOC,
      new HarvestRecord.ConversationThread("t", "T", "https://x/t",
        List.of(new HarvestRecord.Turn("1", "Ann", T, "hello")), Map.of()),
      new HarvestRecord.Tombstone("a"), new HarvestRecord.Seen(List.of("a", "b")), new HarvestRecord.Sweep(),
      new HarvestRecord.Progress(3, 10L), new HarvestRecord.SecretUpdate("token", "s3"),
      new HarvestRecord.Failure(ErrorClass.RATE_LIMITED, "slow down", 30L)))
    assertThat(RecordCodec.decode(RecordCodec.encode(r))).isEqualTo(r);
}
@Test void checkpointCarriesAnyJsonCursor() { /* object cursor round-trips by JsonNode equality */ }
@Test void encodedRecordIsOneLine() { assertThat(RecordCodec.encode(DOC)).doesNotContain("\n"); }
@Test void wireNamesMatchTheSpec() {
  assertThat(RecordCodec.encode(new HarvestRecord.Failure(ErrorClass.AUTH, "no", 5L)))
    .contains("\"type\":\"error\"", "\"class\":\"auth\"", "\"retryAfter\":5");
  assertThat(RecordCodec.encode(DOC)).contains("\"type\":\"document\"", "\"updatedAt\":\"2026-01-02T03:04:05Z\"", "\"format\":\"markdown\"");
}
@Test void acceptsTimestampWithOffset() { /* updatedAt "2026-01-02T05:04:05+02:00" decodes to T */ }
@Test void optionalFieldsMayBeAbsent() { /* document without version, author, metadata decodes */ }
@Test void unknownFieldsAreIgnored() { /* {"type":"sweep","future":1} decodes to Sweep */ }
@ParameterizedTest @ValueSource(strings = {"not json", "[1]", "{}", "{\"type\":\"mystery\"}", "{\"type\":\"tombstone\"}", "{\"type\":\"tombstone\",\"id\":7}",
  "{\"type\":\"seen\",\"ids\":[]}", "{\"type\":\"checkpoint\",\"cursor\":null}", "{\"type\":\"progress\",\"done\":-1}"})
void malformedRecordsAreViolations(String line) { assertThatThrownBy(() -> RecordCodec.decode(line)).isInstanceOf(ProtocolViolation.class); }
@Test void rejectsOverCapText() { /* document text of MAX_TEXT_CHARS + 1 */ }
@Test void rejectsThreadOverCapAcrossTurns() { /* two turns summing to MAX_TEXT_CHARS + 1 */ }
@Test void rejectsDuplicateTurnIds() { }
@Test void rejectsOverCapCursor() { }
@Test void failureMessageIsTruncatedNotRejected() { assertThat(new HarvestRecord.Failure(ErrorClass.FATAL, "x".repeat(5000), null).message()).hasSize(ProtocolLimits.MAX_ERROR_CHARS); }
@Test void secretUpdateNeverPrintsItsValue() { assertThat(new HarvestRecord.SecretUpdate("token", "hunter2").toString()).doesNotContain("hunter2"); }

// RequestCodecTests
@Test void roundTripsAFullEnvelope() { }
@Test void firstRunHasNullCursorAndEmptyLimits() { /* {"protocol":1,"config":{},"secrets":{}} */ }
@Test void requestNeverPrintsSecrets() { assertThat(request.toString()).doesNotContain("hunter2"); }
@ParameterizedTest @ValueSource(strings = {"not json", "{\"config\":{},\"secrets\":{}}", "{\"protocol\":1,\"config\":[],\"secrets\":{}}", "{\"protocol\":1,\"config\":{},\"secrets\":{\"a\":1}}"})
void malformedEnvelopesAreViolations(String line) { }

// BoundedLineReaderTests
@Test void readsLinesThenNullAtEof() { /* "a\nb\n" -> "a","b",null */ }
@Test void stripsOneTrailingCarriageReturn() { }
@Test void truncatedFinalLineIsAViolation() { /* "a\nb" -> "a", then ProtocolViolation mentioning truncated */ }
@Test void invalidUtf8IsAViolation() { }
@Test void oversizeLineStopsReadingAtTheLimit() {
  // An endless stream with no newline: the reader must fail near the limit, not buffer it all.
  CountingEndlessStream in = new CountingEndlessStream();
  assertThatThrownBy(() -> new BoundedLineReader(in, 1024).readLine()).isInstanceOf(ProtocolViolation.class);
  assertThat(in.bytesRead()).isLessThan(64 * 1024);
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :harvester-sdk:test`
Expected: FAIL, at project configuration or compile, since the module and classes don't exist.

- [ ] **Step 3: Implement the module and protocol types**

`build.gradle.kts` follows `modules/shared/build.gradle.kts` (`java-library`, the Spring Boot BOM for versions) with `api("tools.jackson.core:jackson-databind")`, `implementation("tools.jackson.dataformat:jackson-dataformat-toml")`, and JUnit, `junit-jupiter-params` and AssertJ for tests.

Decode with `isString`-style type checks rather than coercing accessors, and wrap every `JacksonException`, `DateTimeParseException` and `IllegalArgumentException` in `ProtocolViolation`. Override `toString` on `SecretUpdate` and `HarvestRequest` to omit secret values. Copy lists and maps defensively in compact constructors, and normalize a null `metadata` to an empty map so equality holds after a round trip.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :harvester-sdk:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts modules/harvester-sdk
git commit -m "Add harvester protocol records and codec"
```

---

### Task 2: Harvester runner

**Files:**
- Create under `modules/harvester-sdk/src/main/java/dev/alvo/pieria/harvester/`: `Harvester.java`, `RecordSink.java`, `HarvesterException.java`, `SinkClosedException.java`, `HarvesterRunner.java`
- Test: `modules/harvester-sdk/src/test/java/dev/alvo/pieria/harvester/HarvesterRunnerTests.java`

**Interfaces:**
- Consumes: `RecordCodec`, `RequestCodec`, `BoundedLineReader`, `HarvestRecord`, `HarvestRequest`, `ProtocolLimits` (Task 1).
- Produces, in `dev.alvo.pieria.harvester`:
  - `interface Harvester { void check(HarvestRequest request) throws HarvesterException; void harvest(HarvestRequest request, RecordSink sink) throws HarvesterException; }`
  - `interface RecordSink { void emit(HarvestRecord record); }`, which writes one line and flushes, throwing `SinkClosedException` when the output is closed
  - `final class HarvesterException extends Exception`: `HarvesterException(ErrorClass errorClass, String message)`, `HarvesterException(ErrorClass errorClass, String message, Long retryAfterSeconds)`, accessors `errorClass()` and `retryAfterSeconds()`
  - `final class SinkClosedException extends RuntimeException`
  - `final class HarvesterRunner`: `static final int EXIT_OK = 0, EXIT_FAILED = 1, EXIT_USAGE = 2`, `static int run(Harvester harvester, String[] args, InputStream in, OutputStream out, PrintStream err)`, and `static void main(Harvester harvester, String[] args)`, which captures the real stdout, calls `System.setOut(System.err)`, then exits with `run`'s result

- [ ] **Step 1: Write the failing tests**

```java
// HarvesterRunnerTests: drive run() with in-memory streams.
private static final String ENVELOPE = "{\"protocol\":1,\"config\":{},\"secrets\":{}}\n";

@Test void harvestWritesRecordsAndExitsZero() { /* emits Sweep; out decodes to [Sweep]; exit 0 */ }
@Test void checkExitsZeroAndWritesNothing() { }
@Test void harvesterExceptionBecomesAnErrorRecord() {
  // HarvesterException(RATE_LIMITED, "slow", 30L) -> out decodes to Failure(RATE_LIMITED, "slow", 30L), exit 1
}
@Test void unexpectedExceptionBecomesFatal() { /* RuntimeException -> Failure class FATAL, exit 1, stack trace on err */ }
@Test void unknownCommandIsAUsageError() { /* args ["nope"] -> exit 2, nothing on out, usage on err */ }
@Test void missingCommandIsAUsageError() { }
@Test void badEnvelopeIsAConfigError() { /* "garbage\n" -> Failure class CONFIG, exit 2 */ }
@Test void unsupportedProtocolIsAConfigError() { /* {"protocol":2,...} -> Failure class CONFIG, exit 2 */ }
@Test void oversizeEnvelopeIsAConfigError() { /* one line over MAX_ENVELOPE_BYTES */ }
@Test void eachRecordIsFlushed() { /* a counting OutputStream sees flush() after every emit */ }
@Test void closedOutputStopsTheHarvester() {
  // OutputStream throws IOException on write. The harvester emits in a loop.
  // emit throws SinkClosedException, the loop ends after one attempt, exit 1, no second write attempted.
}
@Test void catchingSinkClosedStillExitsFailed() {
  // A harvester that swallows SinkClosedException and returns normally must still exit 1, not 0.
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :harvester-sdk:test --tests '*HarvesterRunnerTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement the runner**

The sink is a raw `OutputStream` wrapper, never a `PrintStream`, which would swallow write errors. It records that it has closed and fails any later `emit` immediately, and the runner checks that flag after `harvest` returns. A failure is written best-effort: when the output is already closed, the runner skips the `error` record and returns `EXIT_FAILED`. `check` gets no sink, so it can only report failure by throwing.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :harvester-sdk:test --tests '*HarvesterRunnerTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/harvester-sdk
git commit -m "Add the Java harvester runner"
```

---

### Task 3: Manifest parser

**Files:**
- Create under `modules/harvester-sdk/src/main/java/dev/alvo/pieria/harvester/manifest/`: `HarvesterManifest.java`, `ConfigFieldSpec.java`, `FieldKind.java`, `Capability.java`, `ManifestException.java`, `ManifestParser.java`
- Test: `modules/harvester-sdk/src/test/java/dev/alvo/pieria/harvester/manifest/ManifestParserTests.java`

**Interfaces:**
- Consumes: `HarvestRequest.PROTOCOL_VERSION` (Task 1).
- Produces, in `dev.alvo.pieria.harvester.manifest`:
  - `record HarvesterManifest(String id, String name, String version, int protocol, List<String> command, Set<Capability> capabilities, List<String> network, List<ConfigFieldSpec> config)` with `boolean has(Capability)` and `List<String> resolvedCommand(Path manifestDir)`
  - `record ConfigFieldSpec(String key, FieldKind kind, String label, String hint, boolean required, List<String> options, JsonNode defaultValue)`
  - `enum FieldKind { STRING, SECRET, INT, BOOL, ENUM, LIST }`, wire lower-case
  - `enum Capability { INCREMENTAL, DELETES, OAUTH_DEVICE_CODE }`, wire `incremental`, `deletes`, `oauth-device-code`
  - `final class ManifestException extends RuntimeException`
  - `final class ManifestParser`: `static HarvesterManifest parse(String toml)` and `static HarvesterManifest load(Path file)`

**Rules:** `id`, `name`, `version`, `protocol` and `command` are required. `id` matches the id regex. `protocol` must equal `HarvestRequest.PROTOCOL_VERSION`. `command` is a non-empty array of non-blank strings, never a shell string. `command[0]` is a bare program name or starts with `./`, is not absolute, and contains no `..` segment. Unknown capabilities and kinds are rejected; unknown keys are ignored. Field keys are non-blank and unique. `enum` needs non-empty `options`. A `secret` can't have a `default`. `resolvedCommand` makes a `./` program absolute against `manifestDir` and leaves a bare name for `PATH` lookup.

- [ ] **Step 1: Write the failing tests**

```java
private static final String VALID = """
  id = "jira"
  name = "Jira"
  version = "1.0.0"
  protocol = 1
  command = ["./bin/jira", "--quiet"]
  capabilities = ["incremental", "deletes"]
  network = ["*.atlassian.net"]

  [[config]]
  key = "baseUrl"
  kind = "string"
  label = "Base URL"
  required = true

  [[config]]
  key = "token"
  kind = "secret"
  label = "API token"

  [[config]]
  key = "mode"
  kind = "enum"
  label = "Mode"
  options = ["full", "raw"]
  default = "full"
  """;

@Test void parsesACompleteManifest() { /* every field, including has(INCREMENTAL), kinds, options, default */ }
@Test void optionalSectionsDefaultToEmpty() { /* no capabilities, network or config */ }
@Test void resolvesARelativeProgramAgainstTheManifestDir() { assertThat(m.resolvedCommand(Path.of("/opt/h"))).containsExactly("/opt/h/bin/jira", "--quiet"); }
@Test void leavesABareProgramForPathLookup() { /* ["python3","h.py"] unchanged */ }
@Test void unknownKeysAreIgnored() { }
@ParameterizedTest @MethodSource("invalidManifests") void rejectsInvalidManifests(String toml) { assertThatThrownBy(() -> ManifestParser.parse(toml)).isInstanceOf(ManifestException.class); }
// invalidManifests: not TOML; missing each required key; id "Bad Id"; protocol 2; command as a string; empty command;
// command ["/usr/bin/x"]; command ["../x"]; command ["./a/../../x"]; capability "teleport"; kind "blob";
// enum with no options; secret with default; duplicate field keys.
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :harvester-sdk:test --tests '*ManifestParserTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement the parser**

Read with `TomlMapper.builder().build().readTree(...)` and walk the tree. Wrap parser failures in `ManifestException` naming the offending key.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :harvester-sdk:test --tests '*ManifestParserTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/harvester-sdk
git commit -m "Add the harvester manifest parser"
```

---

### Task 4: Conformance kit

**Files:**
- Create under `modules/harvester-sdk/src/main/java/dev/alvo/pieria/harvester/conformance/`: `ConformanceKit.java`, `ConformanceTarget.java`, `ConformanceOptions.java`, `ConformanceReport.java`, `ProcessRun.java` (package-private), `ConformanceMain.java`
- Modify: `modules/harvester-sdk/build.gradle.kts` (`conformanceJar` task)
- Modify: `AGENTS.md` (module list), `docs/phases/phase-16-knowledge-harvesters.md` (status and protocol clarifications)
- Test: `modules/harvester-sdk/src/test/java/dev/alvo/pieria/harvester/conformance/ConformanceKitTests.java`, `FixtureHarvester.java`

**Interfaces:**
- Consumes: everything from Tasks 1 to 3.
- Produces, in `dev.alvo.pieria.harvester.conformance`:
  - `record ConformanceTarget(HarvesterManifest manifest, List<String> command, Path workingDir)` with `static ConformanceTarget fromManifest(Path manifestFile)`
  - `record ConformanceOptions(ObjectNode config, Map<String,String> secrets, ObjectNode invalidConfig, Duration timeout)`; `invalidConfig` may be null, which skips that check
  - `record ConformanceReport(List<Result> results)` with nested `record Result(String check, Status status, String detail)` and `enum Status { PASS, FAIL, SKIP }`, `boolean passed()` (no `FAIL`), `String render()`
  - `final class ConformanceKit`: `static ConformanceReport run(ConformanceTarget target, ConformanceOptions options)`
  - `final class ConformanceMain`: `--manifest <file> --config <json file> [--secrets <json file>] [--invalid-config <json file>] [--timeout-seconds <n>]`, default timeout 60. Exit `0` passed, `1` failed, `2` usage.

**Checks, by exact name:**

| Check | Passes when |
|---|---|
| `check-accepts-valid-config` | `check` exits 0 with the valid config |
| `check-rejects-invalid-config` | `check` exits non-zero with an `error` of class `auth` or `config`. SKIP when no invalid config |
| `harvest-completes` | `harvest` exits 0, every stdout line decodes, there is no `error` record |
| `harvest-emits-items` | at least one document or thread |
| `capabilities-declared` | `tombstone` and `sweep` only with `deletes`; at least one `checkpoint` with `incremental` |
| `ids-stable-across-runs` | two runs with no cursor produce equal items keyed by id |
| `resume-loses-nothing` | a run from a mid-sequence checkpoint emits every item that came after it in the full run. Overlap is allowed. SKIP without `incremental` or without a checkpoint that has items after it |
| `sweep-is-complete` | any run with `sweep` reported (as items or `seen`) every item from the full run, including the resumed run |
| `limit-respected` | with `maxItemsPerRun = 1`, the run exits 0 with at most 1 item and no `sweep` |
| `exits-when-stdout-closes` | after the kit closes the harvester's stdout, the process exits within 10 seconds |

If `harvest-completes` fails, the checks that depend on its output report FAIL with detail `harvest did not complete`, rather than crashing the kit.

**`ProcessRun`:** clears the child environment apart from `PATH`, `HOME` and `SystemRoot`, writes the envelope and closes stdin, reads stdout through `BoundedLineReader`, drains stderr on a virtual thread into a buffer capped at 64 KiB, and destroys the process tree on timeout.

- [ ] **Step 1: Write the failing tests**

`FixtureHarvester` is a test-source `main` that takes a mode as its first argument and calls `HarvesterRunner.main` with the remaining arguments, so every run is a real child process. Modes change one behaviour from a correct incremental harvester that emits 3 documents with a checkpoint after each, and a final `sweep` on an uncursored, unlimited run:

```java
private static ConformanceReport runMode(String mode) {
  List<String> command = List.of(ProcessHandle.current().info().command().orElseThrow(),
    "-cp", System.getProperty("java.class.path"), FixtureHarvester.class.getName(), mode);
  ConformanceOptions options = new ConformanceOptions(CONFIG, Map.of("token", "t"), INVALID_CONFIG, Duration.ofSeconds(30));
  return ConformanceKit.run(new ConformanceTarget(MANIFEST, command, Path.of(".")), options);
}

private static ConformanceReport.Status status(ConformanceReport report, String check) { /* find by name */ }

@Test void correctHarvesterPasses() { ConformanceReport r = runMode("ok"); assertThat(r.passed()).as(r.render()).isTrue(); }
@Test void printingToStdoutDoesNotBreakTheProtocol() { assertThat(runMode("noisy").passed()).isTrue(); }
@ParameterizedTest @CsvSource({
  "crash, harvest-completes",
  "garbage-stdout, harvest-completes",
  "accepts-anything, check-rejects-invalid-config",
  "no-items, harvest-emits-items",
  "undeclared-tombstone, capabilities-declared",
  "no-checkpoints, capabilities-declared",
  "unstable-ids, ids-stable-across-runs",
  "loses-on-resume, resume-loses-nothing",
  "sweeps-on-resume, sweep-is-complete",
  "ignores-limit, limit-respected",
  "sweeps-when-limited, limit-respected",
  "ignores-closed-stdout, exits-when-stdout-closes"
})
void faultyHarvesterFailsTheNamedCheck(String mode, String check) { assertThat(status(runMode(mode), check)).isEqualTo(ConformanceReport.Status.FAIL); }

@Test void missingInvalidConfigSkipsThatCheck() { /* status SKIP */ }
@Test void withoutIncrementalResumeIsSkipped() { /* manifest with no capabilities, status SKIP for resume-loses-nothing */ }
@Test void aHungHarvesterTimesOutRatherThanHangingTheKit() { /* mode "hangs", 2s timeout: harvest-completes FAIL, returns */ }
@Test void mainReportsResultsThroughItsExitCode() { /* ConformanceMain's testable int run(String[], PrintStream) on the ok fixture via a temp manifest: 0; on a missing --manifest: 2 */ }
```

`ignores-closed-stdout` catches `SinkClosedException` and loops forever, so the kit must destroy it after the check fails.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :harvester-sdk:test --tests '*ConformanceKitTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement the kit and fat jar**

`ConformanceMain` exposes `static int run(String[] args, PrintStream out)` for tests, and `main` exits with its result. Item comparison uses record equality.

`conformanceJar` is a `Jar` task: archive name `harvester-conformance.jar`, `Main-Class` set to `ConformanceMain`, contents from the main output plus `configurations.runtimeClasspath` zip trees, `DuplicatesStrategy.EXCLUDE`, excluding `META-INF/*.SF`, `*.DSA` and `*.RSA`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :harvester-sdk:test --tests '*ConformanceKitTests'`
Expected: PASS

- [ ] **Step 5: Verify the fat jar runs on its own**

Run: `./gradlew :harvester-sdk:conformanceJar && java -jar modules/harvester-sdk/build/libs/harvester-conformance.jar`
Expected: the jar starts, prints usage and exits with code 2.

- [ ] **Step 6: Update the documents**

- `AGENTS.md` module list: add `harvester-sdk`: the harvester protocol, Java runner, manifest parser and conformance kit, deliberately free of other Pieria modules because outside developers consume it.
- Phase 16 doc: set status to phases 1 and 2 complete, and add an amendment listing the protocol details this phase settled: exit codes, `command` as an array restricted to bare or `./` programs, the size limits, `check` signalling through its exit code and an `error` record, and truncated runs never emitting `sweep`.

- [ ] **Step 7: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add modules/harvester-sdk AGENTS.md docs/phases/phase-16-knowledge-harvesters.md docs/superpowers/plans/2026-11-09-harvester-protocol-sdk.md
git commit -m "Add the harvester conformance kit"
```
