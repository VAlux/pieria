# Phase 10 - Bi-Temporal Fact Validity

## Objective

Add bi-temporal validity to memories so facts can be invalidated by time, not only by exact `topic_key` collision. Track two time axes: a VALID-time interval (`valid_from`/`valid_to`, when the fact is true in the world) and the existing `created_at` as TRANSACTION/ingest time (when Pieria learned it). Extend supersession to close prior facts' validity windows instead of only flipping the boolean, make active-set retrieval time-aware, support "as-of" recall, and surface validity windows in synthesis provenance. Hard dependency on Phase 2 (supersession + classification) and Phase 3 (active-set retrieval filtering + temporal extraction); independent of Phase 8 (graph) and Phase 9 (reranker) and may run in parallel with them.

## Scope

- Bi-temporal model behind `MemoryStore`, validity-aware ingestion, supersession, and retrieval.
- SQLite remains the active backend; mirror the schema on the Postgres backend but defer Postgres implementation to Phase 6.
- Reuse `TemporalExtractor` patterns for relative-date resolution; no new model-driven date math.
- No MCP gateway protocol changes beyond surfacing the optional as-of parameter and validity provenance.
- No consolidation/merge logic (that is Phase 11).

## Implementation Sequence

1. Add bi-temporal columns to the memory model.
   - Add nullable `valid_from` and `valid_to` (`Instant`) to `Memory` and the `memories` table via a Flyway migration; keep `created_at` as transaction time.
   - Semantics: `valid_from` is when the fact becomes true in the world (defaults to `created_at` when the transcript states nothing); open-ended `valid_to = NULL` means currently valid; a non-null `valid_to` invalidates the fact at that instant without deleting it.
   - Migrate existing rows with `valid_from = created_at`, `valid_to = NULL` so all current memories stay active.
   - Index `(profile_id, type, topic_key, valid_to)` to keep active-set and as-of filters cheap.
   - Mirror the column definitions in the Postgres schema notes; defer the Postgres `MemoryStore` implementation to Phase 6.

2. Define the validity predicate once.
   - A memory is "valid at instant T" when `superseded = 0` (the row is the live version chain head) AND `valid_from <= T` AND (`valid_to IS NULL` OR `T < valid_to`).
   - Default recall uses T = request timestamp; as-of recall uses the caller-supplied instant.
   - Express the predicate as a single reusable SQL fragment so every channel filter shares it.

3. Populate valid-time during ingestion.
   - Extend classification/extraction so the model may report a stated effective date string per `fact`/`instruction` candidate (free-text date phrase, never a computed delta).
   - Resolve the phrase to an `Instant` deterministically in Java by reusing `TemporalExtractor` date-resolution helpers against the ingest request timestamp; never ask the model to do arithmetic (SPEC §7.2).
   - When no effective date is stated or the phrase is unparseable, set `valid_from = created_at` and `valid_to = NULL`.
   - Store the raw stated date phrase in `payload` for provenance and debugging.

4. Extend supersession to close validity windows.
   - In the existing single-transaction supersession path for keyed `fact`/`instruction` memories sharing `(profile_id, type, topic_key)`: keep marking the prior row `superseded` and keep the `supersedes` version chain.
   - Additionally, set the prior row's `valid_to` to the new memory's `valid_from` (when the prior `valid_to` is still open and the new `valid_from` is later), so the predecessor reads as valid only up to the moment the new fact took effect.
   - Remove the superseded memory's vector in the same transaction, unchanged from Phase 2.
   - Leave `event` and `task` memories append-only; do not auto-close their windows.

5. Add conflict resolution beyond exact-key matches.
   - When a new keyed fact carries a stated `valid_from` earlier than an existing active row's `valid_from` (late-arriving past fact), insert it as superseded-by-time rather than as the chain head, closing its own `valid_to` at the successor's `valid_from`.
   - Keep resolution deterministic and ordering-independent: the row with the latest `valid_from` wins the open-ended (currently-valid) slot for a given `topic_key`.
   - Document that non-keyed types remain append-only and are not conflict-resolved here.

6. Make retrieval active-set filtering time-aware.
   - Thread an "as-of" instant (defaulting to request time) through `RetrievalService` into every channel's active-memory filter.
   - Apply the §2 validity predicate in `searchMemoriesFts`, `exactKeyLookup`, `searchMemoriesByMessageFts`, and `vectorSearch` so expired and not-yet-valid facts are excluded by default.
   - Keep `event`/`task` behavior intact: they have no validity window and remain filtered only by `superseded`.

7. Add the optional as-of recall parameter.
   - Add an optional `asOf` timestamp to the recall request DTO in `shared` and to `POST /v1/profiles/{name}/recall`; default to request time when absent.
   - Pass it through to the validity predicate so "what was true on DATE" returns the fact valid at that instant, not the current head.
   - Surface the same parameter through the MCP `recall` tool in `gateway`.
   - Reject as-of values that are malformed; an absent value is not an error.

8. Surface validity in temporal facts and provenance.
   - Extend `TemporalExtractor` (or a sibling step) to emit a `TemporalFact` per returned keyed memory describing its validity window, e.g. `valid window for <topic_key>` → `from 2026-01-01, currently valid` or `from 2026-01-01 to 2026-03-15`.
   - Inject these validity facts into the synthesis prompt alongside the existing deterministic temporal facts.
   - Include `validFrom`/`validTo` in the per-candidate provenance returned in the recall API response for debuggability.

9. Add ingestion and retrieval observability.
   - Log validity windows assigned at ingest and windows closed during supersession.
   - Log the effective as-of instant used per recall and the count of candidates excluded by the validity predicate.
   - Keep these behind the existing debug/diagnostic surface; default responses stay concise.

## Tests

- Migration tests proving existing Phase 1/2/3 rows get `valid_from = created_at`, `valid_to = NULL`, and remain active.
- Unit tests for the validity predicate at boundaries: exactly `valid_from`, exactly `valid_to` (exclusive), open-ended, and superseded rows.
- Ingestion tests for deterministic resolution of stated effective dates (absolute ISO, relative phrases) and the default-when-absent path, with fake model responses.
- Supersession tests proving the prior row's `valid_to` is closed at the new `valid_from` in one transaction and the version chain is preserved.
- Conflict-resolution tests for late-arriving past facts (ordering-independent winner for the open slot).
- Retrieval tests proving expired/not-yet-valid facts are excluded by default across FTS, exact-key, message-FTS, and vector channels.
- API/gateway tests for as-of recall returning the fact valid at the supplied instant and for malformed as-of rejection.
- Synthesis tests proving validity-window temporal facts and provenance fields appear.
- Run `./gradlew test`.

## Acceptance Criteria

- Every memory has a valid-time interval; existing rows are open-ended and unchanged in behavior after migration.
- Default recall returns only memories valid at request time; superseded and time-expired facts are excluded.
- As-of recall returns the fact valid at the supplied instant rather than the current head.
- Supersession closes the predecessor's validity window in the same transaction while preserving the `supersedes` chain and removing the vector.
- Late-arriving facts resolve deterministically and ordering-independently.
- Synthesis receives deterministic validity-window facts and the recall API returns `validFrom`/`validTo` provenance.

## Risks And Follow-Ups

- Phase 11 (Memory Consolidation & Reflection) depends on this phase: consolidation reasons over validity windows to merge and retire memories, and must consume `valid_from`/`valid_to` rather than inventing a parallel notion of recency.
- Model-stated effective dates are noisy; keep extraction conservative (default to `created_at` on any ambiguity) and revisit prompt versioning with Phase 5 evaluation data.
- Bi-temporal queries can grow expensive; the `(profile_id, type, topic_key, valid_to)` index is a default to be validated under load.
- The Postgres mirror is schema-only here; Phase 6 must implement the same validity predicate and as-of paths to keep the backend swap behavior-preserving.
- As-of recall over the vector channels assumes embeddings of superseded/expired memories are reachable; confirm vector index retention policy does not silently drop historical rows needed for as-of queries.
