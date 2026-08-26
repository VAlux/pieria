# Execution-Trace / Tool-Output Memory — Design

- **Date:** 2026-08-26
- **Feature:** POTENTIAL_FEATURES #6, Phase 12
- **Status:** design approved, implementation plan pending
- **Relationship to `docs/phases/phase-12-execution-trace-memory.md`:** that document remains the
  phase's charter. This design supersedes its Implementation Sequence where the two disagree, because
  it was written on 2026-06-02 against a codebase that did not yet have the graph layer, the code
  index, or the CLI hook subsystem.

---

## 1. Objective

Make tool calls, their outputs, and their outcomes a first-class ingestion source alongside chat
messages, so the highest-value signal for a coding agent — what command validates this module, why
this test failed, what fixed it — becomes durable, retrievable memory.

The intake is harness-driven and non-model-facing. No new MCP tool is added; nothing leaves the
machine.

---

## 2. What changed since the phase doc

Three things landed between 2026-06-02 and now that move this design away from the original sequence:

1. **Phase 8 (graph) and Phase 13 (code index) shipped.** The phase doc treats graph linking as a
   soft dependency to be retrofitted. It is available now, and Phase 13 additionally gives us a
   symbol table to resolve stack frames and test names against. Linking is in scope from the start.
2. **The CLI hook subsystem exists.** `pieria hook <harness> <event>` with per-harness installers
   (`ClaudeCodeInstaller`, `CodexInstaller`, `OpenCodeInstaller`) is the established capture surface.
   The phase doc predates it and speaks vaguely of "the harness lifecycle hook".
3. **Transcript parsing moved daemon-side.** `TranscriptParser` implementations already see every
   `tool_use` / `tool_result` block and deliberately discard them
   (`ClaudeCodeTranscriptParser:21`). This opened a genuine alternative intake — parse traces out of
   the transcript that is already being shipped — which was considered and rejected (see D1).

---

## 3. Decisions

### D1 — Traces enter through an explicit `traces` list on `POST /ingest`

`IngestRequest` gains an optional `traces` list; a new per-tool hook populates it. The existing
`messages`-only payload stays valid and unchanged.

*Rejected: transcript-native extraction.* Extending `TranscriptParser` to emit tool events alongside
messages would need no new wire field, no new hook, and would light up every harness that already
ingests a transcript. It was rejected because the transcript is a lossy, harness-specific rendering
of a tool call: exit codes are frequently absent, output is truncated for display, and the pairing
between a `tool_use` and its `tool_result` has to be reconstructed by id. An explicit structured
event is what the pipeline actually wants, and it keeps the REST surface harness-neutral so a
harness with no transcript file can still contribute.

*Consequence:* capture requires a hook install per harness. Only Claude Code is in scope here (see
§11).

### D2 — The hook spools locally; turn-end hooks drain

`PostToolUse` fires inside the agent's loop, after every tool call. It must not touch the network.
The hook redacts, truncates, and appends one NDJSON line to a per-session spool file, then exits.

Drain policy, settled:

| Hook | `partial()` | Drain |
|------|-------------|-------|
| `Stop` | `true` | Only when the spool is over threshold (size **or** event count) |
| `PreCompact` | `false` | Always |
| `SessionEnd` | `false` | Always |

*Rationale for the threshold on `Stop`:* `Stop` fires every turn. Draining unconditionally would cut
the batch down to a single turn, and a fail→fix pair very often spans turns — the failure is
observed at the end of one turn and the fix lands in the next. Letting the spool accumulate keeps
both inside one extraction window. The threshold bounds how large the spool can grow before it is
flushed anyway.

*Rejected: POST per tool call.* One HTTP round-trip and one pipeline run per tool call, and every
fail→fix pair split across two extractions that cannot see each other.

### D3 — Deterministic events, model-derived recipes

A trace already states the command, the exit code, and the error text. There is nothing to infer.

- **`event` memories are built in Java, with no model call and no verify pass.** They are grounded by
  construction; the verify stage exists to catch invention, which cannot occur here.
- **`instruction` memories are extracted by the small model**, once per batch, over the whole
  surviving sequence in order — so a failure and its fix are visible together. These *do* go through
  the existing `GroundingFilter` and verify stage, because the model is now generalizing.

*Rejected: model over everything.* Uniform with chat ingestion, but pays tokens to restate structured
facts and adds a hallucination surface where none existed.

### D4 — Link into both the memory graph and the code index

Trace memories emit a `GraphFragment` (`command`, `tool`, `test`, `file`, `build tool` entities) into
the Phase 8 graph, *and* resolve file paths and symbol names found in the trace against the Phase 13
code index. Both are deterministic; neither involves a model.

The code join is nearly free: `SqliteMemoryStore.findCodeMemoriesBySymbolIds` (line 1582) already
matches on `payload.$.symbolIds`. A trace memory that writes resolved symbol ids under that existing
key is reachable by the code channels with **no storage change**.

### D5 — Outcome events are keyed, so the latest supersedes

Each outcome `event` carries `topic_key = trace:outcome:<signature>`. The existing supersession
machinery then keeps exactly one active row per command signature — the most recent outcome — and
demotes older ones to history, dropping their vectors in the same transaction.

This bounds vector-index growth, makes a stale "build failed" unreachable through recall, and
introduces no new mechanism. Phase 10 validity windows are not required.

*Note:* supersession is ordered by `MemoryTimes.knowledgeTime`, which reads `payload.stated_at`
before falling back to store time. Trace events therefore **must** stamp `stated_at`, or a spool
drained late would be ordered by ingest time and could fail to supersede correctly.

---

## 4. Architecture

### 4.1 `shared` — wire shape and redaction

**`dev.alvo.pieria.api.request.TraceEventDto`**

```java
public record TraceEventDto(
  @NotBlank String tool,      // "Bash", "Edit", …
  String args,                // the invocation: command line, or path for an edit
  String output,              // stdout/stderr, already capped by the hook
  @NotNull TraceStatus status,// SUCCESS | FAILURE | UNKNOWN
  Integer exitCode,
  String error,
  Instant startedAt,
  Instant endedAt) {}
```

`TraceStatus` is a sibling enum with lenient `fromWire` parsing, matching `MemoryType`'s convention.

**`IngestRequest`** gains `List<@Valid TraceEventDto> traces`. `messages` loses `@NotEmpty`, and an
`@AssertTrue` guard requires at least one of the two lists to be non-empty. All three existing
convenience constructors are retained unchanged, so every current caller compiles as-is.

**`dev.alvo.pieria.tools.Redaction`** (shared, per `.claude/rules/utility-code-placement.md` — both
the CLI hook and the daemon use it):

- `truncate(String, int budget)` — head + tail with an elision marker, so the error line at the end
  of a long log survives. **Applied before the redaction regexes**, so the hook's cost is bounded by
  the budget rather than by the raw output size.
- `redactSecrets(String)` — token/key/password/bearer/PEM patterns → `[redacted]`. Returns a
  `Redacted(String text, int hits)` so hit *counts* can be logged without the content.
- `normalizePaths(String, Path repoRoot, Path userHome)` — absolute repo paths → `./`-relative,
  home → `~`.

Redaction runs twice by design: in the hook before anything reaches disk, and in the daemon on
receipt so direct API callers are covered too. It is idempotent.

### 4.2 `cli` — capture and spool

**`CcPostToolUseCommand`** (`pieria hook claude-code post-tool-use`), registered in
`ClaudeCodeInstaller.HOOK_EVENTS` under `PostToolUse`.

`HookInput` currently reads only `session_id` and `transcript_path`. It is extended with the
`PostToolUse` fields — `tool_name`, `tool_input`, `tool_response` — kept optional so the existing
lifecycle hooks are unaffected.

**`TraceSpool`** (`cli/modules/hook`):

- Path: `$PIERIA_HOME/spool/traces/<sanitized-session-id>.ndjson`
- `append(TraceEventDto)` — `FileChannel.lock()` around a single write of one line. An explicit lock
  rather than reliance on `O_APPEND` atomicity: a redacted line can exceed `PIPE_BUF`, and Claude
  Code may run tool calls in parallel.
- `drain()` — lock, read all lines, truncate the file to zero, unlock. Returns the parsed events.
- Growth cap: on append, if the file exceeds `spool-max-bytes`, drop the oldest half. A runaway
  session degrades rather than filling the disk.
- Stale-spool sweep: on any drain, delete spool files older than `spool-retention-days`.

**`AbstractIngestHookCommand`** gains a spool step before the transcript ingest.

The turn-end hooks POST raw NDJSON to `/ingest/transcript`, not JSON to `/ingest`, so traces cannot
ride the same request. The hook makes a **separate `POST /ingest` carrying `{sessionId, traces}` and
no messages** — which is precisely why `messages` had to become optional (D1). It is best-effort: a
trace ingest failure is logged and never blocks the transcript ingest.

Whether to drain is decided by `partial()` plus the thresholds from D2.

### 4.3 `daemon` — the trace pipeline

New package `dev.alvo.pieria.ingestion.trace`. `ProfileController.ingest` routes a non-empty `traces`
list to `TraceIngestionService`, which runs its own path and does **not** go through `Chunker` or
unified extraction.

```
traces
  │
  ├─ 1. redact          Redaction (again, for direct API callers)
  │
  ├─ 2. persist raw     insertMessages(role="tool", content=canonical summary)
  │                     INSERT OR IGNORE over a content-addressed id ⇒ idempotent,
  │                     and MessageFtsChannel covers raw traces for free.
  │
  ├─ 3. filter          TraceRelevanceFilter (deterministic)
  │
  ├─ 4. events          TraceMemoryFactory  — Java only, no model
  │      └─ graph       TraceGraphBuilder → GraphFragment
  │      └─ code link   TraceCodeLinker   → payload.symbolIds
  │      └─ store       MemoryStore.store(profileId, memory, graph)
  │                     ⇒ supersession + vectorization outbox, both existing
  │
  └─ 5. recipes         TraceRecipeExtractor — small model, once per batch
         └─ GroundingFilter + existing verify stage
         └─ store as instruction, keyed trace:recipe:<signature>
```

**`CommandSignature`** — the normalization that both topic keys derive from. Deterministic:

1. Take `tool` plus the invocation (`args` for `Bash`, the target path for edits).
2. Lowercase, collapse whitespace, strip a leading `./`, normalize absolute paths to repo-relative.
3. Drop flag tokens (`-*`, `--*`) and their values, and drop purely numeric tokens.
4. Keep the first 4 remaining tokens.
5. Slugify: non-alphanumerics → `-`, collapse runs.

`./gradlew test --info` → `gradlew-test`. `./gradlew :daemon:test` → `gradlew-daemon-test`.

**`TraceRelevanceFilter`** — deterministic rules, in order:

- **Always keep** any trace with `status = FAILURE`, regardless of tool.
- **Always keep** `Bash`, `Edit`, `Write`, `MultiEdit`, `NotebookEdit`.
- **Drop** tools on the denylist (`Read`, `Grep`, `Glob`, `LS`, `TodoWrite`, `NotebookRead`,
  `WebSearch`, `WebFetch`, `Task`) when they succeeded — a successful read-only listing carries no
  durable signal.
- **Collapse** repeats: within one batch, for a given `(signature, status)` keep only the last
  occurrence.
- **Skip unchanged outcomes** (`skip-unchanged-outcomes`, default on): drop a trace whose active
  `trace:outcome:<sig>` row already records the same status *and* the same error digest. Re-writing
  "still passing" every turn is churn with no new information. **Consequence, deliberate:** an
  outcome event's `occurred_at` is when the current outcome was *first* observed, not when it was
  last confirmed — "passing since", which is the more useful reading. A status change or a different
  error always writes.

**`TraceMemoryFactory`** — one `event` per surviving trace:

- `content` — a built sentence, e.g. ``` `./gradlew test` failed (exit 1): GroundingFilterTests >
  grounded FAILED ```
- `topicKey` — `trace:outcome:<signature>`
- `payload` — `{"source":"trace", "tool":…, "command":…, "status":…, "exit_code":…,
  "occurred_at":…, "stated_at":…, "symbolIds":[…]}`
- `occurred_at` **and** `stated_at` are both the trace's `endedAt`. `occurred_at` because the command
  genuinely ran then; `stated_at` because supersession ordering reads it (see D5). Neither is
  `Memory.createdAt`, which is store time and would misorder a late-drained spool.
- `embedText` — the declarative content plus deterministic interrogatives built from the signature
  ("how do I run the tests here", "why does `<sig>` fail", "does `<sig>` pass"). Templates, not model
  output.

**`TraceRecipeExtractor`** — small tier, at most once per ingest batch.

- **Cost guard:** skipped entirely when the batch contains no failures *and* no signature unseen in
  this profile. A batch of routine successes yields no new recipe.
- Input: the ordered compact summaries of the surviving batch.
- Prompt: `daemon/src/main/resources/prompts/extract-trace-recipes.txt`, loaded via the existing
  `PromptTemplateLoader`.
- Output candidates run through the existing `GroundingFilter` against the concatenated summaries,
  then the existing verify stage. Capped at `max-recipes-per-batch`.
- Stored as `instruction`, `topicKey = trace:recipe:<signature>`, same `source: "trace"` payload flag.

**`TraceGraphBuilder`** — entity types from the feature description: `command`, `tool`, `test`,
`file`, `build tool`. Triples:

| Source | Relation | Target | When |
|--------|----------|--------|------|
| `tool` | `invoked` | `command` | always |
| `command` | `failed_in` | `test` | failure with an identifiable test |
| `command` | `validates` | `module` | signature names a module |
| `command` | `touched` | `file` | edits |

Names go through the existing `EntityNormalizer` before id computation. Per-memory caps reuse the
existing `max-graph-entities-per-memory` / `max-graph-triples-per-memory` properties.

**`TraceCodeLinker`** — deterministic extraction of code references, then resolution:

- Java stack frames — `at com.foo.Bar.baz(Bar.java:52)` → qualified name + file
- Gradle test failures — `ClassName > methodName FAILED`
- Bare file paths — `[\w./-]+\.(java|kt|scala|js|ts|tsx|scss|py|go|rs)`

Resolved with `CodeIndexStore.findSymbolsByQualifiedName` first, then `findSymbolsByName`. Hits are
capped at `max-linked-symbols` and written to `payload.symbolIds`. A profile with no code index
simply resolves nothing.

### 4.4 Retrieval

**No new channel.** Trace memories participate in `MemoryFtsChannel`, `ExactKeyChannel`,
`DirectVectorChannel`, `HydeVectorChannel`, `GraphChannel`, and — via `payload.symbolIds` —
`CodeGraphChannel`. Raw traces are additionally covered by `MessageFtsChannel` through the
`role="tool"` rows.

An optional post-fusion boost for `payload.source = "trace"` is configurable
(`pieria.retrieval.trace-boost`, default `1.0` = off), so "how do I…" queries can be tuned to prefer
procedural recipes during Phase 5 evaluation without a code change.

### 4.5 Configuration

A nested `Trace` record on `PieriaProperties.Ingestion`, following the existing pattern:

```properties
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
pieria.retrieval.trace-boost=1.0
```

`enabled=false` makes the daemon accept and discard `traces`, so the feature can be switched off
without uninstalling the hook.

---

## 5. Identity and idempotence

`ContentId.forTrace(profileId, sessionId, tool, canonicalArgs, status, endedAt)` — SHA-256 truncated
to 128 bits, matching every other id in `ContentId`. `profileId` is in the hash input, consistent
with messages and memories: identical traces coexist across profiles, re-ingest within one profile
is a no-op.

Raw trace rows use `insertMessages`, which is `INSERT OR IGNORE`. Re-draining a spool that was
already ingested inserts nothing and derives nothing new, because the outcome events are keyed and
`skip-unchanged-outcomes` short-circuits them.

---

## 6. Observability

Per-ingest counts at INFO, consistent with the existing per-stage ingestion logging: traces received,
deduped, truncated, redaction **hits** (count only — never the content), filtered out (by rule),
events stored, events superseded, recipes attempted / verified / dropped, symbols linked.

Nothing is sent off-machine.

---

## 7. Testing

Unit:

- `ContentId.forTrace` — fixed vectors; re-ingest is a no-op.
- `Redaction` — secret patterns, head+tail truncation preserving the trailing error line, path
  normalization. Assert redacted content reaches neither stored content nor `embedText`.
- `CommandSignature` — flag stripping, module-qualified Gradle tasks, slugification stability.
- `TraceRelevanceFilter` — each rule in isolation: failure always kept, denylisted success dropped,
  in-batch repeats collapsed, unchanged outcome skipped.
- `TraceSpool` — append/drain round-trip, concurrent appends under lock, growth cap drops oldest,
  retention sweep.
- `TraceMemoryFactory` — payload contract, `stated_at`/`occurred_at` both set from `endedAt`.
- `TraceCodeLinker` — stack frame, Gradle failure line, and bare path extraction.

Service (fake `ModelGateway`, per AGENTS.md — no Ollama, no network):

- Deterministic events are produced with **zero** model calls.
- Recipe extraction runs once per batch and is skipped by the cost guard on an all-success batch.
- A verify-stage drop discards the recipe but leaves the events stored.

Storage:

- Keyed outcome supersession: run 1 failure → run 2 success supersedes it, the old vector is removed,
  and only the current outcome is vector-reachable.
- Graph fragment persisted; edges deactivate with their memory on supersession.

API:

- `messages`-only ingest is unchanged; `traces`-only and mixed payloads both work; repeated calls
  produce no duplicates.
- Both lists empty → 400.

CLI (per AGENTS.md: no test seams — point `--daemon-url` at a throwaway localhost HTTP stub):

- `Stop` under threshold does not drain; over threshold drains.
- `SessionEnd` and `PreCompact` always drain.
- A daemon-down trace POST does not prevent the transcript ingest.

Retrieval:

- "how do I run the tests" surfaces the trace-derived `instruction`.
- "why did GroundingFilterTests fail" co-retrieves the trace event and the linked symbol.
- Filterable by type and by `payload.source = "trace"`.

`./gradlew test` must pass.

---

## 8. Risks

- **Redaction is best-effort pattern matching.** Patterns need maintenance, and the truncation budget
  must not clip the error line that carries the signal — hence head+tail rather than head-only.
- **`PostToolUse` is on the agent's critical path.** The budget check must precede the regexes, and
  the hook must never contact the network. A slow hook degrades every tool call in the session.
- **Superseded history grows unbounded.** Rows are cheap (no vector) and `skip-unchanged-outcomes`
  removes the dominant source of churn, but Phase 11 consolidation is the designated folder for
  repetitive trace memories, as the phase doc anticipates.
- **`skip-unchanged-outcomes` changes what `occurred_at` means** for trace events. Documented above
  and in the payload contract; worth revisiting if evaluation shows recency ranking suffers.
- **The `event`/`instruction` mapping is a heuristic.** Phase 5 evaluation may justify a dedicated
  memory type if procedural recall underperforms. The `source: "trace"` payload flag exists so that
  can be measured without a migration.
- **Parallel tool calls.** The spool lock handles concurrent appends, but a hook killed mid-write
  could leave a partial line. Drain skips unparseable lines, consistent with how `TranscriptParser`
  implementations already handle malformed records.

---

## 9. Out of scope

- **Transcript-native trace extraction** (rejected in D1). The `TranscriptParser` implementations
  continue to discard `tool_use` / `tool_result`.
- **Codex and OpenCode capture.** The REST surface is harness-neutral, so adding them is a CLI-only
  change — but it depends on each harness exposing a per-tool hook event, which has not been
  verified. Claude Code's `PostToolUse` is confirmed; the others are follow-up work.
- **TTL / `expires_at`** (POTENTIAL_FEATURES #10) and **Phase 10 validity windows**. D5 makes neither
  a prerequisite.
- **Consolidation of repetitive trace memories** — Phase 11.
- **A new top-level `MemoryType`.** Traces map onto `event` and `instruction` with a payload flag, per
  the phase doc's step 7.
