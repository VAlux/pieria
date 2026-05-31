# Phase 11 - Memory Consolidation and Reflection

## Objective

Add a background consolidation and reflection layer that periodically reviews the active memory set off the hot path:
cluster related memories, merge near-duplicates into canonical versions via the existing supersession chain, derive
higher-level observation memories from clusters, retire stale facts using bi-temporal validity, and optionally
consolidate graph nodes/edges. This is the fourth Tier-1 feature - the "sleep consolidation" / Hindsight-observations
analogue - and it strengthens the store over time without ever blocking ingest or recall.

## Scope

- Background consolidation only, modeled on the Phase 2 transactional-outbox + virtual-thread worker pattern.
- Reuses Phase 3 vector similarity for clustering and Phase 2 supersession for merges.
- HARD dependency on Phase 8 (graph entities/edges, used for clustering and for optional graph consolidation) and Phase
  10 (bi-temporal validity windows, used to retire stale facts). This phase is sequenced after both for exactly these
  reasons.
- Largely independent of Phase 9 (reranker). Consolidation of Phase 12 execution-trace memories is out of scope until
  Phase 12 exists.
- No new REST surface beyond a local status/trigger seam. No telemetry off-machine.
- Postgres backend parity is deferred to its own backend work; SQLite is the active backend here.

## Implementation Sequence

1. Add a derived observation memory class.
  - Introduce a derived/observation flag (or a `MemoryType` extension) on `Memory`, vector-eligible, distinct from
    extracted `fact`/`event`/`instruction`/`task`.
  - Observations carry provenance: a list of source memory ids they were derived from, persisted in `payload` and in a
    dedicated provenance table for queryability.
  - Observations participate in supersession like any other memory (a re-derived observation supersedes its
    predecessor).
  - Excluded from being a clustering seed for further observation derivation beyond a configurable depth, to prevent
    recursive summary-of-summary growth.

2. Add the consolidation outbox.
  - Add a `consolidation_outbox` table holding consolidation candidate seeds (a memory id or a topic key marked dirty),
    with `attempts` and `last_error`, mirroring the vectorization outbox.
  - Enqueue a seed transactionally whenever a vector-eligible memory is stored or superseded, so consolidation work is
    driven by real change rather than a blind full-table scan. Justification: the outbox keeps consolidation
    incremental, idempotent, and consistent with the rest of the system; a periodic blind sweep would rescan unchanged
    memories and scale poorly.
  - De-duplicate seeds (insert-or-ignore) so repeated writes to the same topic key do not pile up work.
  - Add `MemoryStore` methods: `drainConsolidationOutbox(batchSize)`, `recordConsolidationFailure(id, error)`,
    `deleteConsolidationRow(id)`, `consolidationOutboxDepth()`.

3. Add the consolidation worker and scheduler.
  - Add `ConsolidationWorker` with a `consolidateOnce()` method returning a per-run report, holding only the
    draining/processing logic (unit-testable without a scheduler), mirroring `VectorizationWorker`.
  - Add `ConsolidationScheduler` gated by `@ConditionalOnProperty` (`pieria.consolidation.enabled`), default
    conservative cadence via `pieria.consolidation.interval-ms`, disabled in tests.
  - Run blocking model and embedding calls on virtual threads; never let a batch failure kill the scheduler thread.
  - Bound work per sweep: `pieria.consolidation.max-seeds-per-run`, `pieria.consolidation.max-merges-per-run`,
    `pieria.consolidation.max-observations-per-run`.

4. Cluster related active memories.
  - For each drained seed, build a candidate set from three signals: vector KNN over `memories_vec` (Phase 3), shared
    `topic_key`, and shared graph entities/edges (Phase 8).
  - Fuse the signals into clusters using configurable thresholds: `pieria.consolidation.vector-similarity-threshold`,
    minimum shared-entity count, and a maximum cluster size.
  - Use the SMALL model only to confirm cluster membership where lexical/vector signals are ambiguous; do not invoke the
    large model here.
  - Skip clusters below a minimum size; record a "no-op" outcome and delete the seed.

5. Merge near-duplicate and overlapping memories.
  - Within a cluster, identify near-duplicate keyed memories (`fact`/`instruction`) using vector distance plus a
    SMALL-model structured merge decision (merge / keep-separate / supersede-direction).
  - Produce a single canonical memory and apply it through the existing supersession path: in one transaction mark the
    absorbed memories superseded, point the canonical row's `supersedes` at the prior active row, and delete the
    absorbed memories' vector rows.
  - Never hard-delete; the version chain remains the audit trail.
  - Leave `event` memories append-only; only collapse `event` memories when they are exact content-addressed duplicates.

6. Derive observation memories.
  - For clusters that represent a coherent higher-level pattern (not just duplicates), invoke the LARGE model once to
    synthesize a single observation memory, mirroring the two-tier strategy (small for structure, large for genuine
    synthesis).
  - Build `embed_text` for the observation and enqueue it on the vectorization outbox like any vector-eligible memory.
  - Record provenance links from the observation to every source memory.
  - Suppress derivation when an existing active observation already covers the same cluster (detected by provenance
    overlap and topic key); re-derive and supersede only when the source set materially changed.

7. Retire stale facts via validity windows.
  - Using Phase 10 bi-temporal validity, find active `fact` memories whose valid-time window has closed as of the run
    timestamp.
  - Mark them superseded (retired) in the same transaction and delete their vector rows; do not derive observations from
    retired sources.
  - Temporal evaluation is deterministic Java arithmetic against the run timestamp, not a model call.

8. Optionally consolidate the graph.
  - Behind `pieria.consolidation.graph-enabled`, merge duplicate Phase 8 entity nodes and redundant edges discovered
    during clustering, repointing edges to the canonical node in one transaction.
  - Keep this a no-op when Phase 8 graph data is absent so the worker degrades gracefully.

9. Guarantee idempotency and bounded re-runs.
  - Make every consolidation operation re-runnable: a cluster already merged/derived produces a no-op on the next
    sweep (detected via provenance and supersession state).
  - Cap merges and derivations per run; carry remaining seeds to the next tick rather than processing unbounded work.
  - Drop a seed only after its transaction commits; on failure, increment `attempts` and abandon as a poison message
    past `pieria.consolidation.max-attempts`.

10. Expose consolidation observability.
  - Per-sweep report counts: seeds drained, clusters found, memories merged, observations derived, memories retired,
    graph nodes/edges merged, errors.
  - Log locally only; expose depth/last-run via the existing local status seam. No telemetry off-machine.
  - Surface the report from `consolidateOnce()` so the eval harness can measure consolidation effects.

## Tests

- Migration tests for the `consolidation_outbox` table, the observation/provenance schema, and backward compatibility of
  existing rows.
- Unit tests for cluster fusion thresholds, cluster size bounds, and merge-direction decisions with fake SMALL-model
  responses.
- Service tests for observation synthesis with a fake LARGE model, asserting provenance links to all source memories.
- Storage integration tests proving merges supersede (never hard-delete), delete absorbed vector rows in the same
  transaction, and that retired facts leave the active vector set.
- Idempotency tests: running `consolidateOnce()` twice over the same fixture produces no additional merges or
  observations (no runaway growth).
- Bounding tests: `max-merges-per-run` / `max-observations-per-run` cap work and remaining seeds survive to the next
  run.
- Worker tests for batch draining, retry increments, poison-message abandonment, and seed deletion only after commit.
- Validity-retirement tests using Phase 10 windows against a fixed run timestamp.
- Run `./gradlew test`.

## Acceptance Criteria

- Consolidation runs entirely in the background and never blocks ingest or recall.
- Near-duplicate memories are merged into a canonical memory through the supersession chain, with absorbed vector rows
  removed in the same transaction.
- Derived observations are vector-eligible and always traceable to their source memories via persisted provenance.
- Stale facts past their Phase 10 validity window are retired (superseded), not deleted.
- Re-running consolidation over an unchanged store performs no additional merges or derivations.
- Work per sweep is bounded by configuration with conservative defaults; consolidation is disable-able.
- Per-sweep counts are logged locally with no off-machine telemetry.

## Risks And Follow-Ups

- Aggressive thresholds risk over-merging distinct memories; keep thresholds conservative and treat them as eval-tunable
  defaults until Phase 5 data confirms them.
- Observation derivation depends on large-model quality; keep derivation measurable and individually disable-able for
  comparison.
- Recursive summary-of-summary growth must stay bounded by the derivation-depth limit; revisit if observation volume
  grows.
- Once Phase 12 (execution-trace memories) exists, extend consolidation to cluster and summarize execution traces;
  explicitly out of scope here.
- Postgres/pgvector parity for the consolidation outbox and graph consolidation is deferred to the server-mode backend
  work.
- Graph consolidation correctness depends on Phase 8 entity-resolution quality; gate it behind config until proven.
