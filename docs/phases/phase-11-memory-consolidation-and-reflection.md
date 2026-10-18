# Phase 11 — Nested, overlapping memory clusters

## Objective

Organize original memories into a directed acyclic graph of knowledge containers. A cluster may contain memories, clusters, or both; a child may have several parents. Original memories and assertions remain intact. Each container has a stable identity, immutable membership revisions, and separately versioned synthesis. Recall chooses useful detail across levels. The lazy wiki uses these containers as its primary topic structure and generates articles on demand.

This design supersedes the earlier exclusive, flat consolidation proposal. Cluster maintenance does not merge or retire original memories. Bi-temporal validity remains authoritative.

## Storage and invariants

SQLite is the initial backend. All graph mutations use `MemoryStore.writeClusters` and the daemon's single writer. Persist clusters, structure revisions, direct edges, overrides, synthesis revisions, child revision pins, claim support and coverage, runs, work items, audit events, and schedules.

- Enforce profile isolation, unique direct edges, no self-membership, and no cycles transactionally.
- Compute depth from the longest path. Allow at most four cluster levels, five direct parents per node, 20 direct members, and 500 distinct original descendants.
- Manual detach excludes that edge from automatic reattachment. Dissolution preserves children and their other parents and protects the dissolved group from immediate recreation. Restoration revalidates the current graph.
- Keep structure navigable while synthesis is absent or stale. A published synthesis is a vector-eligible `cluster` memory linked to its container and bypasses ingestion extraction and supersession.
- At read time, check synthesis structure, pinned child revisions, original assertion revisions, visibility, and forgetting against the recall's single world-time, knowledge-time, and transaction snapshot.

## Construction and maintenance

Bootstrap eligible originals through a paginated temporal snapshot. Then process durable changes for assertions, embeddings, graph evidence, and cluster revisions. Candidate discovery should combine bounded vector, lexical, topic-key, and distinctive-entity neighbors, at most 30 per seed. Source-sensitive rules distinguish ordinary knowledge, execution traces, and code summaries.

Validate coherence with the small model and synthesize with the large model, using an 8,000-token input and 1,200-token output budget. Verify grounding and coverage before publication. Automatic clusters need at least three distinct originals. Higher-level clusters must connect two meaningfully different contributions and explain a broader subject. Reject redundant wrappers and groups justified only by generic vocabulary.

Deduplicate original evidence before confidence checks, token accounting, and generation. Every published claim traces to original assertion revisions. Membership, support, and coverage are separate: citation alone does not establish detail coverage. Index claims for lexical and semantic matching. Refresh affected ancestors bottom-up. Model calls occur outside transactions; publication rechecks inputs, revisions, overrides, and worker fencing.

Runs are durable and profile scoped, with one unfinished run per profile and one generation worker globally. States are `queued`, `running`, `pausing`, `paused`, `waiting`, `completed`, `completed_with_errors`, `cancelled`, and `failed`. Persist work stages, outputs, attempts, leases, and checkpoints. Retry transient failures three times and continue independent branches. Recover expired leases after restart. A default slice stops after 100 seeds, 10 publications, or 30 minutes. Scheduling is opt-in at 02:00 in a persisted timezone; deduplicate local dates and preserve explicit pauses.

## Recall

Search originals, published summaries, and indexed claims. Expand a bounded set of relevant parents and children under the same temporal snapshot. Apply existing fusion and reranking, then select complementary results.

- A leaf may nominate parents; ancestry alone does not qualify a parent. Require matching claims or relevant coverage from multiple child branches.
- Count each original evidence unit once per channel and across graph paths.
- Suppress an ancestor, descendant, or overlapping peer only when its useful matching contribution is already represented. Keep complementary overlaps.
- Prefer a smaller synthesis, then stable ID, for equivalent relevance and coverage. Return an original when a summary omits its matching detail.
- Apply provenance filters to generated results. Excluding code-derived material excludes any mixed synthesis that depends on it while leaving eligible descendants available.

Evidence-tier recall stays deterministic apart from query embedding. Generation never runs on recall. Return stable cluster ID, revisions, direct and distinct-source counts, and inspection reference. Debug output should explain promotion, fallback, and suppression.

## Lazy wiki and interfaces

Map each cluster to one stable topic page. A shared child has one page with a stable primary breadcrumb and links to other parents. Preserve old wiki URLs as legacy pages unless their scope and evidence match a new cluster. Generate articles lazily from eligible validated claims and selected original evidence, citing original assertions. Fingerprint selected structure, synthesis, and source revisions. Never feed wiki prose into clustering. Retain entity, code, and unclustered-evidence views.

Provide hierarchy browsing, search, freshness, direct and distinct-source counts, claims and originals, relationship history, revision diffs, and editing controls. REST endpoints live under `/v1/profiles/{name}`; CLI commands live under `pieria cluster`. Writes require expected revisions and return `409` on conflict. Expensive work returns `202` with a durable reference. Management MCP tools are deferred. Export and profile deletion must cover the complete subsystem.

## Validation and acceptance

Use fake models. Test diamonds, shared children, cycles, concurrent edits, bounds, manual operations, original preservation, source deduplication, repeated-summary grounding, narrow and broad recall, omitted-detail fallback, historical snapshots, forgetting, scheduled validity, source changes during generation, recovery, partial failures, pause and cancel fencing, scheduler deduplication, stable wiki pages and citations, profile isolation, export, redaction, and CLI controls. Run `./gradlew test`.

Compare hierarchical retrieval with original-only and flat-cluster baselines for answer coverage, unsupported claims, redundancy, latency, and generation cost. Ship the hierarchy as a retrieval improvement only after that comparison demonstrates its benefit. Native compilation and deployment are separate steps.

## Implementation status

The SQLite graph, immutable revisions, a transitive dependency index, read-time temporal synthesis eligibility, durable bottom-up refresh work, validity-boundary activation, runs and schedules, model-validated generation with usage accounting, REST and CLI controls, console browser, stable lazy-wiki pages, lexical and semantic claim search, bounded parent nomination, clustering export, and namespace-based higher-level construction are implemented. Recall debug output records granularity and overlap decisions. Restoring membership queues a durable regeneration run and returns its reference. Automatic seed discovery uses topic-key, graph-entity, lexical, and vector neighbors with bounded admission. Trace and code neighbors require a shared topic key, symbol, exact command, or named code module in addition to lexical/vector similarity. Each run persists a temporal snapshot; coherence reads use it, and synthesis defers if a current source assertion is incompatible with it. After bootstrap, a durable change cursor limits lexical/vector seed discovery to changed memories. The original-only/flat/hierarchical benchmark remains acceptance work.
