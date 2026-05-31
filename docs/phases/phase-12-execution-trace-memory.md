# Phase 12 - Execution-Trace Memory

## Objective

Add tool calls, their outputs, and their outcomes as a first-class ingestion source alongside chat messages. Capture
what commands were run, what worked, what errored, and what fix resolved it, then route these traces through the
existing extract → verify → classify pipeline so the highest-value signal for a coding agent ("how do I run the tests
here", "why does build X fail") becomes durable, retrievable memory. This is the fifth Tier-1 differentiator and stays a
harness-driven, non-model-facing intake.

## Scope

- Trace intake, trace-aware extraction/classification, redaction, and trace participation in retrieval.
- Hard dependency on Phase 2: reuse content-addressed IDs, the extract/verify/classify stages, supersession, and the
  vectorization outbox. Do not fork a parallel write path.
- Soft dependency on Phase 8 (graph): link tool/file/command entities into the relationship graph when that layer
  exists.
- Soft dependency on Phase 10 (validity windows): outcomes go stale, so trace-derived memories should carry validity
  metadata where Phase 10 is available.
- Sequence this phase after Phase 8 so graph linking is available rather than retrofitted.
- Intake remains a HOOK-driven, non-model-facing surface, consistent with §10. No new model-facing MCP tool.
- SQLite remains the active backend; nothing leaves the machine.

## Implementation Sequence

1. Define the trace event wire shape.
  - Add a `TraceEventDto` with `tool` (name), `args` (string or JSON), `output`, `status` (`success` | `failure` |
    `unknown`), optional `exitCode`, optional `error` text, and `startedAt`/`endedAt` timestamps.
  - Model the coding-agent essentials: commands run, file edits (path + nature of change), and test/build results.
  - Keep timestamps explicit so temporal arithmetic stays in Java (per the existing design constraint), not the model.

2. Extend the intake endpoint without breaking chat ingestion.
  - Extend the `POST /v1/profiles/{name}/ingest` request to accept an optional `traces` list alongside (or instead of)
    `messages`; require at least one of the two to be non-empty.
  - Keep the existing `messages`-only payload valid and unchanged.
  - Map `ingest` to the same daemon handler; do not add a model-facing MCP tool. The harness lifecycle hook ships traces
    the same way it ships conversations.

3. Make trace ingest idempotent and content-addressed.
  - Derive a trace message/source ID via SHA-256 over `sessionId`, `tool`, canonical `args`, `status`, and the relevant
    timestamp, truncated to the established stable width.
  - Store the raw trace event under the existing raw-source storage so re-shipping the same trace is insert-or-ignore
    and a no-op, exactly as with raw messages.
  - Preserve source order and provenance so derived memories trace back to the originating trace event.

4. Redact and bound trace content before extraction.
  - Apply size caps: truncate large `output`/`error` blobs to a configured character budget, keeping head and tail
    context; never store full logs verbatim as durable memory.
  - Redact secrets: scrub tokens, API keys, passwords, and obvious credential patterns from `args`, `output`, and
    `error` before storage and before any embedding.
  - Normalize machine-specific absolute paths to repo-relative form where deterministic, preserving the path's meaning
    without leaking the host layout.
  - Run redaction in Java before the content reaches the model gateway, so secrets never enter prompts or embeddings.

5. Add a trace relevance filter ahead of extraction.
  - Drop transient/no-signal traces (e.g. successful read-only listings, trivially repeated commands) using
    deterministic rules first.
  - For surviving traces, build a compact trace summary string (tool, intent, status, key error line, fix if observed in
    adjacent events) as the extraction input rather than raw output.
  - Keep the filter configurable and observable so noise rejection can be tuned in evaluation.

6. Run trace-aware extraction and verification.
  - Feed the trace summary into the Phase 2 small-model extract → verify stages.
  - Verification must confirm the derived memory is supported by the trace and is durable, not a one-off transient
    result; drop unsupported or noisy candidates rather than guessing.
  - Reuse the existing structured-output parsing via `ModelGateway`; do not introduce a separate model path.

7. Classify trace-derived memories onto existing types, with a payload flag.
  - Map "this happened" (a command ran, a test failed once) to `event`, carrying `occurred_at` from the trace timestamp.
  - Map reusable procedure/recipe knowledge ("running X in this repo fails with Y; the fix is Z", "tests are run with
    `<cmd>`") to `instruction`, with a normalized `topic_key` so supersession applies when the recipe changes.
  - Do not add a new top-level `MemoryType`; instead tag provenance in `payload` with a `source: "trace"` flag plus
    structured fields (`tool`, `status`, `exitCode`, `command`). This keeps the four-type model intact, lets these
    memories ride existing channels, and makes them filterable. Justify any future dedicated type only if evaluation
    shows the `event`/`instruction` mapping loses signal.

8. Supersede stale procedural learnings.
  - Because trace-derived `instruction` memories are keyed, a newer recipe for the same `topic_key` (e.g. the test
    command changed) supersedes the old one in a single transaction, removing its vector, per Phase 2/§5.6.
  - Where Phase 10 validity windows exist, stamp outcome `event` memories with a validity window so stale "build X
    failed" outcomes can expire rather than mislead.

9. Index trace-derived memories through the existing outbox.
  - Enqueue trace-derived `event` and `instruction` memories for embedding via the Phase 2 outbox; `task` exclusion is
    unchanged.
  - Build `embed_text` from interrogative queries (e.g. "how do I run the tests", "why does the build fail") plus the
    canonical declarative content, so procedural traces surface under natural agent questions.

10. Make traces discoverable and weightable in retrieval.
  - Trace-derived memories participate in the existing FTS, exact-key, and vector channels with no new channel
    required (Phase 3).
  - Support filtering/boosting by the `payload.source = "trace"` flag and by type, so callers can prefer procedural
    recipes for "how do I…" queries; keep any weighting configurable for Phase 5 evaluation.
  - When the Phase 8 graph exists, link `tool`, `command`, and `file` entities from traces into the relationship graph
    so related outcomes and recipes co-retrieve.

11. Expose trace-ingest observability.
  - Log per-stage counts: trace events received, deduped, redacted, filtered out, extracted, verified, and stored.
  - Log redaction hits and truncation events without logging the redacted content itself.
  - Do not send telemetry off-machine.

## Tests

- Unit tests for the trace content ID (fixed vectors), proving re-ingesting the same trace is a no-op.
- Unit tests for redaction (secret patterns, size caps, path normalization) proving secrets never reach stored content
  or embed text.
- Unit tests for the relevance filter dropping transient traces and keeping signal-bearing ones.
- Service tests with fake model responses covering trace extraction, verification drop, and `event`/`instruction`
  classification with the `source: "trace"` payload flag.
- Storage tests for idempotent trace ingest and supersession of trace-derived keyed instructions.
- API tests proving `messages`-only ingest still works, `traces`-only and mixed payloads work, and repeated calls do not
  duplicate memories.
- Retrieval tests proving trace-derived memories surface for "how do I run the tests" / "why does build X fail" and are
  filterable by type and trace source.
- Run `./gradlew test`.

## Acceptance Criteria

- `POST /ingest` accepts structured trace events alongside or instead of chat messages, and the existing chat payload is
  unchanged.
- Re-shipping the same trace is idempotent.
- Secrets and oversized logs are redacted/capped before storage and embedding; nothing leaves the machine.
- Transient/noisy traces are filtered before extraction.
- Durable trace knowledge is stored as `event` or `instruction` with a `source: "trace"` payload flag, not a new memory
  type.
- Trace-derived memories participate in existing retrieval channels and are filterable/weightable by type and source.
- No new model-facing tool is added; trace intake is hook-driven only.

## Risks And Follow-Ups

- Phase 11 (consolidation) will later consolidate repetitive trace memories (e.g. many "test run" events) into compact
  summaries; this phase should store them in a shape consolidation can fold cleanly.
- Redaction is best-effort pattern matching; secret patterns must be maintained and tested, and the size budget tuned to
  avoid dropping the error line that carries the actual signal.
- The `event`-vs-`instruction` mapping is a heuristic; evaluation in Phase 5 may justify a dedicated trace/procedure
  type if recall on procedural questions underperforms.
- Validity windows for stale outcomes depend on Phase 10; until then, stale "build failed" events risk misleading
  retrieval and should be deprioritized by recency.
- Graph linking depends on Phase 8; if traces ingest before the graph is ready, entity links are deferred rather than
  backfilled inline.
