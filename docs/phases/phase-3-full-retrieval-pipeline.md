# Phase 3 - Full Retrieval Pipeline

## Objective

Replace the Phase 1 recall lookup with the full read pipeline: query analysis, FTS, topic key lookup, raw message fallback, direct vector search, HyDE vector search, weighted Reciprocal Rank Fusion, deterministic temporal extraction, and final synthesis.

## Scope

- Retrieval quality and ranking.
- SQLite FTS5 and embedded vector search.
- No MCP gateway or packaging work.
- Postgres search is deferred to Phase 6.

## Implementation Sequence

1. Add FTS5 support for SQLite.
   - Create `memories_fts` and `messages_fts` as **external-content** FTS5 tables (`content='memories'`/`content='messages'`, `content_rowid='rowid'`) with the **`porter`** tokenizer, per SPEC §5.2.
   - Add triggers to keep the external-content FTS rows synchronized on insert, update, and delete/forget.
   - Include active-memory filtering so superseded rows do not dominate results.
   - Add migration tests that prove existing Phase 1 and Phase 2 rows are searchable after migration.

2. Validate embedded vector support.
   - Use `sqlite-vec` (the SPEC §4 / §5.2 choice), loaded at startup via the xerial driver's `enableLoadExtension`; document DuckDB VSS only as the open-question fallback (SPEC §18), not as a decision to make here.
   - Confirm the native extension loads on the supported development platform before making it mandatory.
   - Add a `memories_vec` virtual table (`vec0`) keyed by `memory_id` with the configured embedding dimension (`FLOAT[n]`).
   - Fail startup clearly if configured embedding dimensions do not match the vector table.
   - Keep a feature flag or capability check so local development can still run tests without native vector extensions.

3. Complete vector persistence.
   - Implement `MemoryStore.upsertEmbedding(...)`.
   - Implement `MemoryStore.deleteEmbedding(...)` for superseded memories.
   - Backfill vector jobs for existing active vector-eligible memories.
   - Ensure `task` memories remain excluded from vector indexing.

4. Implement query analysis.
   - Use `ModelGateway` to produce ranked topic keys, FTS terms, synonyms, and a HyDE declarative statement.
   - In parallel, embed the raw query.
   - Embed the HyDE statement after analysis.
   - Add deterministic fallback analysis when the model is unavailable so exact/FTS lookup can still run.

5. Implement retrieval channels behind stable interfaces.
   - Memory FTS channel over active memories.
   - Exact topic key channel over active keyed memories.
   - Raw message FTS channel as a lower-priority safety net.
   - Direct vector channel using the raw query embedding.
   - HyDE vector channel using the hypothetical answer embedding.
   - Return ranked `RetrievalCandidate` values with channel metadata and source snippets.

6. Run retrieval channels in parallel.
   - Use virtual threads or structured concurrency-style lifecycle management available to Java 25.
   - Bound each channel with timeout and limit settings.
   - Allow partial retrieval results when one non-critical channel fails, while logging the failure.
   - Treat exact topic key lookup and local storage failures as hard failures.

7. Implement weighted Reciprocal Rank Fusion.
   - Start with `k = 60`.
   - Weight exact topic key highest.
   - Weight memory FTS, HyDE vector, and direct vector as primary signals.
   - Weight raw message FTS lower as a safety-net channel.
   - Break score ties by recency, then deterministic memory ID ordering.
   - Keep weights configurable for evaluation tuning in Phase 5.

8. Add deterministic temporal extraction.
   - Detect dates, durations, relative references, and arithmetic requests in Java before synthesis.
   - Resolve against the request timestamp and known event timestamps.
   - Inject computed temporal facts into the synthesis prompt.
   - Do not ask the model to perform date arithmetic.

9. Rework synthesis inputs.
   - Pass the original query, temporal facts, fused memory candidates, raw-message fallbacks, and provenance to the large/synthesis model.
   - Require the answer to state when there is insufficient memory evidence.
   - Return memory IDs and channel provenance in the API response for debuggability.

10. Add retrieval diagnostics.
   - Log per-channel latency, hit counts, and fusion scores.
   - Expose debug fields only through an explicit request flag or test-only surface.
   - Keep default API responses concise.

## Tests

- Migration tests for FTS tables, triggers, and active-memory filtering.
- Unit tests for query analysis parsing, RRF scoring, channel weighting, tie-breaking, and temporal arithmetic.
- Retrieval fixture tests covering keyed lookup, memory FTS, raw message fallback, direct vector stubs, HyDE vector stubs, and mixed-channel fusion.
- API tests for recall responses with provenance and insufficient-evidence behavior.
- Worker/storage tests proving superseded memories are removed from vector results.
- Run `./gradlew test`.

## Acceptance Criteria

- Recall runs all five retrieval channels where configured capabilities allow them.
- Fused rankings are deterministic for a fixed fixture.
- Superseded memories and task vectors do not appear in vector search results.
- Temporal questions receive pre-computed deterministic facts in the synthesis prompt.
- Recall degrades gracefully when vector support is disabled but still uses FTS and keyed lookup.

## Risks And Follow-Ups

- The embedded vector dependency may have platform-specific native loading issues; keep capability checks and tests explicit.
- RRF weights should be treated as defaults until Phase 5 evaluation data exists.
- HyDE quality depends on model behavior, so the channel should be measurable and easy to disable for comparison.
