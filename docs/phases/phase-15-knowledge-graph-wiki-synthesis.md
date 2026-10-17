# Phase 15 — Lazy Profile Wiki

Status: opt-in implementation available; release quality evaluation remains pending.

Updated: 2026-09-27.

Design rationale: [Lazy wiki exploration](../LAZY_WIKI_EXPLORATION.md).

This specification replaces the earlier eager, regenerated-on-ingest Phase 15 proposal. The wiki is
a profile-scoped derived view of canonical memories: build a searchable map before generating prose,
generate an individual article on deliberate opening, and mark changed articles stale for explicit
refresh. Identity, temporal visibility, provenance, and forgetting are first-release requirements.

## Implementation checkpoint (2026-09-28)

The daemon now implements the wiki projection, REST endpoints, durable article jobs, source guards,
and the console Markdown reader. Enable **Wiki → Enable profile wiki** in profile configuration,
or set `pieria.wiki.enabled=true` as a daemon default. Open **Wiki** in the console to browse and
search. Opening an ungenerated article requests synthesis; existing articles require an explicit
refresh after their evidence changes. The API and reader remain current-only.

Implementation entry points are `wiki/WikiProjectionService`, `WikiEvidenceSelector`, `WikiService`,
`WikiWorker`, `storage/SqliteWikiStore`, and `static/js/console/wiki.js`. Flyway V2 adds derived tables
and mutation/forgetting triggers without rewriting canonical memory history. SQLite access remains
behind `MemoryStore`; writes use short transactions, while wiki reads avoid reserving its single
writer slot. A dedicated wiki model client disables transport
retries so the durable queue controls the two-attempt budget.

The first implementation uses the specification's conservative completeness fallback: corpus or
grouping-policy changes and temporal boundaries trigger a resumable, paginated profile recheck.
It seeds anchors, assigns evidence, then reconciles descriptors and structure. Bounded candidate
matching uses retained embeddings. Keyed and graph-anchored sources score bounded candidates;
unanchored sources can still use profile-wide vector search. Maintenance never invokes chat or
generates missing embeddings. Writer transactions process at most eight sources/pages, and each
worker cycle runs multiple short batches within a time budget. Directory reconciliation and reader
evidence are bounded; non-directory reconciliation can still read all page memberships.
Region-specific routing and representative large-profile performance evaluation remain follow-up work.

Verification covers fresh/V1 migration, resumable projection, identity-preserving splits, merge
confirmation, deduplicated jobs, temporal expiry, unchanged-input reuse, changed-input publication
rejection, lease fencing, forgetting, profile deletion/isolation, token budgets, citation validation,
audit privacy, and provider attempt counts. The repository test suite and existing JavaScript checks
pass. A temporary daemon and local model stub were used to check search, first-open generation,
Markdown, citations, and desktop/mobile layout in a browser.

This checkpoint does not complete the release evaluation described below: semantic thresholds,
large-profile lag/churn, and claim grounding still need representative corpus/model evaluation.
No native distribution or installed daemon was updated.

## 1. Delivery boundary and invariants

### First release

- Directory, descriptor/evidence search, and an Obsidian-style reader in the existing web console:
  topic navigation on the left, rendered Markdown in the reading area, and linked pages/sources
  alongside the article. This is the web representation of the wiki, not an Obsidian plugin.
- Stable topic/entity pages, semantic evidence assignment, and automatic topic splits and merges.
- Cited, cached, bounded, on-demand narrative generation through the existing synthesis tier.
- Durable projection/generation work, temporal freshness, and immediate forgetting protection.
- SQLite persistence through profile-scoped `MemoryStore` methods; shared REST DTOs in `shared`.

Deferred: Markdown export, Obsidian synchronization, historical wiki browsing, manual page editing,
user pins, consolidation integration, background narrative warming, and CLI/MCP wiki tools. Postgres
implementation remains deferred to Phase 6. Phase 11 consolidation is not a prerequisite.

### Invariants

1. Directory reads and lexical search make zero model calls. Semantic search may embed a query,
   but never invokes a chat model. Graph/embedding gaps leave usable key/lexical discovery.
2. GET requests never spend synthesis tokens. Only explicit evaluation can generate; the console
   requests initial evaluation after deliberate article navigation, not hover, preview, or prefetch.
3. Ingestion updates structural work/freshness, never automatically generates prose.
4. Page identity is independent of title, membership, and narrative content. Navigation links do not
   recursively generate neighbors or descendants.
5. All source reads use shared temporal visibility and forgetting semantics, not `superseded` flags.
   Tasks remain excluded from embedding. The wiki never changes canonical memories.
6. Ordinary stale prose stays readable with explicit Refresh. Forgotten evidence immediately blocks
   serving dependent derivatives, including prior versions and descriptor metadata.
7. Track consumed evidence and the scope where uncited new evidence could change a page. A stored
   `stale=false` flag alone is insufficient proof of freshness.
8. No model call holds a SQLite transaction open. Only the daemon writes the embedded database.
9. Wiki artifacts never become canonical memories or enter recall/vectorization.

## 2. Repository integration

The graph explorer exposes bounded slices, not `MemoryStore.graphSnapshot`. Add paginated internal
enumeration rather than reconstructing a whole graph through a console endpoint.

Reuse `TemporalSnapshot`, `MemoryStore.atSnapshot`, and the existing validity/forgetting predicate.
Introduce a separate ordered wiki change sequence: graph, embedding, and forgetting mutations do
not all advance the current temporal sequence. Capture both watermarks consistently with sources.

`TaskRegistry` is in-memory. Reuse progress vocabulary where useful, but persist wiki jobs and leases
independently. An in-memory task ID is not a durable generation record.

Reuse the console Markdown renderer/profile routing, prompt loader, `Hash`, `Tokens`, text similarity,
and inference usage accounting. Generic helpers belong in `shared`; wiki-specific logic stays in the
daemon. Add these responsibilities under `dev.alvo.pieria.wiki`:

| Component | Responsibility |
| --- | --- |
| `WikiProjectionService` | Bootstrap, change routing, descriptors, memberships, reconciliation |
| `WikiSearchService` | Descriptor/evidence search and rank fusion |
| `WikiEvidenceSelector` | Snapshot-bound bundles, coverage, fingerprints, temporal deadlines |
| `WikiGenerationService` | Evaluation, cache reuse, validation, conditional publication |
| `WikiWorker` and scheduler | Bounded durable work, leases, retries, recovery |
| `WikiController` | Profile-scoped REST; no direct SQL/model orchestration |

Extend `MemoryStore` with typed source-enumeration, feed/checkpoint, atomic projection, dependency,
page/version, job-lease, and conditional-publication operations. SQLite can delegate to a wiki SQL
helper. Distinguish unsupported storage from an empty wiki; clients never access the database.

Add typed `ModelGateway.synthesizeWiki(...)` and a dedicated prompt through the existing prompt
loader. Use the synthesis tier/provider configuration, not a new model tier.

`docs/PLAN.md` and `docs/SPEC.md`, referenced by repository instructions, are absent in this checkout.
This phase document is the implementation contract; the exploration remains historical rationale.

## 3. Domain, persistence, and migration

Page kinds: `directory`, `topic`, `entity`. Lifecycle: `active`, `retired`, `redirect`.

Generation (`absent`, `queued`, `generating`, `ready`, `failed`) and freshness (`current`, `needs-check`,
`stale`, `unavailable`) are independent. A ready body can remain stale during refresh or after its
failure. An absent body has freshness `unavailable`; it has no article freshness claim.

Allocate opaque page IDs once and persist their subject anchors. Separate descriptor revision from
scope revision: navigation-only changes do not change evidence selection. Track rendering upgrades
separately from evidence staleness when only prompt/model settings change.

| Record | Required fields and responsibility |
| --- | --- |
| `wiki_profile_state` | Profile, bootstrap cursor/snapshot, initialization state, corpus change watermark, contiguous projection checkpoint, policy version, reconciliation state |
| `wiki_pages` | Profile/page ID, kind, title, aliases, scope recipe, anchors, lifecycle, descriptor/scope revisions, current version, next temporal boundary |
| `wiki_memberships` | Profile/page, memory/assertion/revision references, core/contextual role, assignment reason/score, projection revision |
| `wiki_links` | Profile/source/target page, `contains` or `related`, provenance where source-derived |
| `wiki_page_versions` | Profile/page/version, immutable Markdown, fingerprint, source snapshot/watermarks, coverage, citations, generation config, creation time, suppression state |
| `wiki_dependencies` | Version/descriptor owner, materialized source references and scope selectors, including title/alias/excerpt provenance |
| `wiki_changes` | Ordered event, profile, mutation kind, source references, old/new routing anchors, processing/retry state |
| `wiki_jobs` | Profile/job/page, input identity, intent/status, attempts, lease owner/expiry, fence, result reference, safe error code |
| `wiki_redirects` | Profile/from/to page IDs for canonical resolution |
| Descriptor FTS | Profile-qualified titles, aliases, anchors, representative excerpts, published with descriptor revision |

Use profile-qualified uniqueness/foreign keys for every relationship. Index reverse dependencies,
scope selectors, membership by page/memory, pending work, lease expiry, and temporal boundaries.
Enforce one live job per profile/page/input/configuration.

Add an incremental Flyway migration; do not rewrite V1 or reset databases. It creates empty derived
state and mutation capture. Bootstrap existing profiles in resumable batches when enabled. Publish
descriptor/membership/navigation/search updates atomically. Hard profile deletion removes wiki state
and fences work so late completions cannot recreate it.

Memory NDJSON export stays unchanged and does not preserve wiki identities; database backups do.
Retained versions are internal cache/provenance, not historical browsing. Forgetting can purge their
contents regardless of normal cache retention.

## 4. Projection and semantic organization

### Seeds and directory

Enumerate sources in cursor batches of eight under a captured snapshot. Persist bootstrap progress;
writes after its watermark remain in the feed for catch-up. Current forgetting markers apply even
to the bootstrap snapshot.

- Seed topic pages from exact keys and code paths, entity pages from visible supporting memories.
- Include current source-code fact records with provenance. Exclude `code:summary:*` narratives
  until their full derivation/freshness can be tracked; direct unversioned code-graph enrichment is
  also deferred.
- Keep disconnected/unassigned memories in a searchable evidence pool; never discard them because
  no suitable page exists. Tasks use lexical/key paths.
- Build fixed roots: Topics, Entities, Code, Unassigned Evidence. Group by namespace/entity type/path;
  use root, group, and article levels, with narrower children beneath split article overviews.
- Sort by normalized title, then ID. Derive labels from keys/paths/entities and representative source
  phrases, without a model naming/synopsis pass. Record metadata provenance for forgetting.

### Semantic assignment, policy version 1

Structure construction makes no chat-model calls or new embedding requests. Reuse retained memory
embeddings, lexical similarity, and entities.

For each changed memory, include exact-anchor placements, up to 20 lexical and 20 vector neighbors,
and pages sharing entities. Map neighbors to page candidates, retain the best 50 semantic candidates,
and compare with up to eight core representatives per page. Exact core placements are not lost to
the cap. Choose representatives deterministically across subanchors and memory IDs; contextual
additions do not recursively become seeds. Admit at most three contextual memberships.

Subject compatibility requires a shared exact/path anchor, a non-hub entity, or lexical similarity
at least `0.20`. An entity appearing in more than `max(10, 10% of eligible memories)` is a hub and
cannot establish compatibility alone. Use the existing word-shingle similarity for lexical scores.

| Decision | With compatible embeddings | Without compatible embeddings |
| --- | --- | --- |
| Admit contextual membership | Cosine ≥ `0.80` and subject compatibility | Lexical ≥ `0.35` and subject compatibility |
| Retain contextual membership | Cosine ≥ `0.75` and subject compatibility | Lexical ≥ `0.25` and subject compatibility |

Rank by maximum representative similarity, then lexical similarity, then page ID. Primary placement
is the exact topic/path page, otherwise the highest-ranked qualifying page. Do not compare embeddings
from incompatible model/dimension configurations. Embedding completion/replacement triggers a
structural event; missing embeddings degrade to lexical assignment.

Conflicting assertions may belong to the same subject. Membership does not resolve disagreement,
merge canonical facts, or decide which assertion is true.

### Automatic splits and merges

Limit maintenance to ten structural operations per sweep; persist identities and reconcile locally.

- Consider splitting topic pages above 80 core sources or 16,000 estimated source tokens. Partition
  by stable key/path subanchors first. Otherwise use deterministic farthest-first grouping: start
  with the lowest source ID, add the least-similar source not admitted by existing seeds, and assign
  sources to their best qualifying seed under the assignment policy. Break ties by ID; keep
  residual evidence on the overview rather than forcing assignment.
- Split only when at least two coherent children each have three core sources. An indivisible large
  subject stays one page with bounded generation and explicit partial coverage.
- Preserve the original ID as an overview; persist/reuse child identities and narrower scopes on
  later sweeps. Do not repeatedly allocate new children for unchanged partitions.
- Merge sibling topic pages only with a shared specific subject anchor, membership Jaccard ≥ `0.80`,
  and representative cosine ≥ `0.90` (lexical ≥ `0.60` without embeddings), on two completed sweeps.
- Exclude directories, parent/child pairs, distinct entity identities, and split children whose core
  partitions are disjoint. Keep the older page (ID tie-break), union scopes/aliases, redirect
  absorbed IDs, and flatten redirect chains. Containment and redirects remain acyclic.
- Record structural reasons/affected IDs. Recheck consumed descriptors/scopes without automatically
  generating prose. Renames preserve identity; unsupported subjects retire and can reactivate only
  for the same persisted subject. Never reuse IDs for unrelated topics.

## 5. Search without generation

Lexical mode combines descriptor FTS with current memory FTS/exact-key evidence. Semantic mode adds
one query embedding and memory-vector retrieval. Provider failure returns lexical results with a
degradation indicator. Use one snapshot across routes and profile/temporal filtering before limits.

Collect at most 100 descriptor and 100 memory candidates. Give literal title/key matches the first
result bucket. Fuse remaining route ranks with RRF (`k=60`), aggregating at most three ranked memory
hits per page with weights `1`, `1/2`, `1/4`; tie-break by page ID. Diversify by multiplying a later
result's score by `0.5` if membership Jaccard exceeds `0.80` with an already selected page.

Generated/visited state never boosts relevance. If recall ranking is reused, preserve list position,
not exposed `RecallCandidate.score`. Reuse retrieval primitives rather than public recall orchestration
if the latter introduces extra snapshots, chat inference, or unrelated usage accounting.

Return title, scope, source-derived matching excerpts, evidence count, canonical ID, and generation/
freshness state. Return unmapped memory hits as evidence results. Live evidence can reveal new
memories during projection lag; report incomplete coverage. Search creates/generates no pages.

## 6. Bounded article generation

### Selection and grounding

Resolve redirects, verify the expected descriptor/current-version state, and reconcile the target
scope through a captured watermark. If projection is not ready, return a retryable conflict rather
than synthesizing incomplete membership. Capture one current-world/current-knowledge snapshot,
materialize an immutable evidence bundle, fingerprint it, and reuse or join/enqueue generation.
Release database resources before inference.

Defaults: 40 sources, 8,000 estimated input tokens including prompt overhead, 1,500 output tokens.
Also honor configured model context limits; reject impossible budgets before inference. `Tokens`
is an estimate, not an exact provider tokenizer.

Select core before contextual evidence, round-robin across fact groups/subanchors with stable ID
ordering. Keep available conflict groups together; if a group cannot fit, omit it as a group and
disclose incomplete coverage. Oversized sources may use bounded excerpts marked as truncated.
Fewer than two selected distinct sources yields evidence-only presentation without inference.

Bundles contain source text/excerpts, memory/assertion/revision references, supported relationships,
provenance, allowlisted page links, Java-computed temporal facts, policy version, and selected/omitted/
truncated counts. Prefer absolute dates; never ask the model to perform date math. A source supporting
multiple relations is not independent corroboration. Overview pages consume their own evidence and
child descriptors, not child bodies; record consumed descriptor revisions.

Request structured sections with Markdown and evidence markers. Every factual paragraph/list item
requires an allowlisted source. Java resolves citations to actual provenance. Validate schema, size,
citation IDs, and internal links; disallow raw HTML/arbitrary generated external links. Preserve
disagreements and disclose gaps. Invalid output fails without replacing a usable body. Citation
integrity does not prove entailment: unsupported claims require explicit release evaluation.

### Cache identity

Hash canonical ordered inputs: profile/page identity, scope revision, selected text/excerpts and
assertion revisions, consumed relationships/descriptors, coverage/selection policy, prompt/schema,
effective generation settings/language, and temporal facts actually used.

Record exact times and watermarks for provenance, but exclude ever-changing request time/global
corpus revision from the reuse key. Navigation-only edits do not invalidate prose unless consumed.
Equivalent reselection clears a check without synthesis; changed evidence marks the body stale.

### Durable jobs and publication

Job states: `queued`, `generating`, `succeeded`, `failed`, `obsolete`, `cancelled`. Store safe progress/
error metadata; result references resolve through the normal version serving guard.

- Default one active wiki synthesis globally, queue of 100. Deduplicate before capacity checks.
- Use a 180-second inference deadline, renewable 30-second leases, ten-second heartbeat, and fencing
  tokens. Lease loss prevents publication regardless of model completion.
- Retry transient failures once with bounded backoff; malformed output requires explicit retry.
  Coordinate provider retries with the two-attempt total; account for every actual provider call.
- Recover expired leases after restart within the attempt limit, rechecking inputs first. Changed
  inputs obsolete the job; recovery never silently enqueues a new-input generation.
- Recheck profile existence, fence, descriptor/scope state, relevant changes, temporal boundaries,
  and forgetting in a short conditional publication transaction. Discard obsolete completions;
  older jobs cannot replace newer versions. A new-input evaluation requires another request.

Model/projection failures never fail a successful ingest/recall. Durable event capture remains
transactionally coupled to writes: a source transaction must not commit with its required event lost.

## 7. Freshness, temporal boundaries, and forgetting

### Change capture and scope checking

Capture memory/assertion creation, correction, graph attachment/removal, forgetting, and embedding
completion/replacement used by grouping. Record old/new anchors; unchanged replays do not create
spurious work. Capture independently of feature enablement so re-enabling cannot miss changes.
Disabled profiles run no workers/inference; accumulated work may be coalesced safely on enablement.

Coalesce events and publish memberships/descriptors/links/search/checkpoints atomically. Advance only
a contiguous completed checkpoint. Failed work backs off and remains recoverable; never skip it to
report current. Prune only acknowledged events below completed checkpoints.

Track materialized dependencies (consumed sources/revisions/relationships/metadata) and scope
dependencies (keys, fact groups, entities, paths, semantic regions, including uncited candidates).
Route through old/new anchors, reverse dependencies, prior memberships, and candidate matching.
Representative changes schedule reassignment. If bounded routing cannot establish completeness,
dirty the containing namespace/path region; use the profile for unkeyed/cross-region ambiguity.
Reconcile before certifying freshness. Broader deterministic checking does not mean broader synthesis.

Responses distinguish corpus, projection, and narrative-input watermarks. Guarded old prose can be
served as `needs-check` during lag, never falsely current. Equivalent reselection restores current
without inference; changed inputs become stale for explicit refresh.

### Time without writes

Track the earliest relevant validity boundary across all scope candidates, including scheduled
replacements not selected yet. Check it on access and in maintenance. Resolve through the shared
predicate: override expiry can restore the underlying assertion. No ingest is needed for freshness
to change. Current-only MVP rejects supplied historical cutoffs; old bodies alone do not implement
historical descriptor/membership/search/dependency reconstruction.

### Forgetting

Commit forgetting and derivative suppression together. Cover bodies/prior versions, titles/aliases,
excerpts/search documents, and persisted bundles. Mark dependent owners suppressed synchronously;
reads and publication also check live forgetting state rather than trusting worker progress.
Purge contents asynchronously and rebuild from remaining evidence. Retain only safe identity/status
where needed for link continuity. Forgetting must not restore competing assertions.

The profile audit filter currently captures response bodies. Add metadata-only handling for wiki
routes/task events: no article/excerpt content, query text, raw output, or provider error payloads in
audit bodies/FTS, task results, or logs. Retain operation/profile/page/job IDs, status, timings, counts.
Use `Cache-Control: no-store` and no localStorage/IndexedDB article cache. A refreshed read clears
suppressed content already displayed. Already delivered/copied bytes cannot be remotely revoked;
existing non-wiki audit behavior is outside this phase.

## 8. REST contract

All endpoints below have prefix `/v1/profiles/{name}`. Unknown profiles are not created by reads.
GET may request structural checking but never enqueue narrative synthesis.

| Method/path | Contract |
| --- | --- |
| `GET /wiki` | Directory, counts, enable/bootstrap state, corpus/projection watermarks |
| `GET /wiki/search?q=&mode=` | Descriptor/mapped evidence search; lexical default, semantic opt-in |
| `GET /wiki/pages/{id}` | Descriptor, links, body, citations, coverage, freshness, pending job |
| `GET /wiki/pages/{id}/evidence` | Paginated current evidence and membership roles |
| `POST /wiki/pages/{id}/evaluate` | Initial generation, explicit refresh, or retry |
| `GET /wiki/jobs/{id}` | Durable progress, terminal state, result version reference |

Directory/evidence use opaque cursors and `limit` (default 30, max 100); directory may filter parent.
Bind cursors to profile/filter/projection revision; obsolete cursors return a restartable conflict.
Search is bounded ranking, not exhaustive pagination: default 20/max 50 results with coverage data.

Shared DTOs: `WikiDirectoryResponse`, `WikiSearchResponse`, `WikiPageResponse`, `WikiEvidenceResponse`,
`WikiEvaluateRequest`, `WikiEvaluateResponse`, `WikiJobResponse`. Include canonical IDs and machine
states/error codes, not only human labels.

Evaluation carries `expectedDescriptorRevision`, nullable `expectedVersionId`, and intent (`initial`,
`refresh`, `retry`). Initial never implicitly refreshes stale prose. Refresh requests current evidence
and rendering settings. Matching concurrent calls join a job; obsolete expectations are rejected.

| Status | Meaning |
| --- | --- |
| `200` | Read, cached reuse, or evidence-only evaluation with reason |
| `202` | Queued/joined job with ID and `Location` |
| `307` | Same-profile canonical redirect preserving method/body |
| `400` | Invalid parameters/intent/budget or unsupported historical cutoffs |
| `404` | Unknown/cross-profile profile, page, or job |
| `409` | Obsolete expected state, disabled evaluation, or projection not ready; safe reason/retry guidance |
| `410` | Retired page with safe identity/status and no forgotten text |
| `429` | Queue full after deduplication, with retry guidance |

Disabled directory reads return enabled=false and an explanation. Directories or insufficient
eligible evidence never invoke a model. All serialization, including job-result resolution, applies
the forgetting guard.

## 9. Obsidian-style web reader

Use an Obsidian-style information layout within the existing console theme, not an external app or
new UI service. Render Markdown through the existing safe renderer.

- Left navigation: directory/tree, search, generation/freshness badges; search includes ungenerated
  pages and unassigned evidence.
- Main pane: title, scope, coverage/freshness notice, rendered article, and immediately usable evidence
  while generation runs. Sparse pages remain useful source cards.
- Context pane: related/incoming links, heading outline, expandable citations/sources. Narrow screens
  collapse panels instead of squeezing the article.
- Canonical URL: `/index.html?view=wiki&profile=<name>&page=<id>`; preserve bookmarks and browser
  back/forward navigation and resolve redirects.
- Deliberate link/direct navigation to an absent article requests initial evaluation once. Fetching
  descriptors, displaying/hovering links, previews, and prefetch do not. Directories never generate.
- Stale bodies show explicit Refresh and source-change counts. Failed initial generation leaves
  evidence and Retry available; failed refresh retains the guarded previous body.
- Poll active jobs every two seconds while visible; stop on terminal state or navigation. Abort old
  requests and reject late responses after profile/page changes.
- Resolve citations/internal links through allowlisted IDs; support keyboard navigation and focus
  restoration. Explain current as aligned with profile evidence, not independently verified truth.

Expose projection lag and partial/evidence-only coverage in reader terms. Keep detailed watermarks
in API/diagnostics rather than requiring users to understand transactions.

## 10. Configuration and diagnostics

Add wiki settings to existing global/profile resolution and schema-driven console forms. Process
worker limits cannot be raised by per-profile overrides. Use existing provider/model settings;
never put credentials in wiki configuration snapshots or fingerprints.

| Setting | Default | Ownership |
| --- | --- | --- |
| Enabled | `false` | Global default/profile override |
| Projection batch/interval | `8` / `5000 ms` | Global; SQLite writer batches are capped at eight |
| Active generation/queue | `1` / `100` | Global |
| Generation deadline/attempts | `180 s` / `2` | Global |
| Sources/input/output tokens | `40` / `8000` / `1500` | Global default/profile override within model limits |
| Semantic admit/retain | `0.80` / `0.75` | Global default/profile override |
| Lexical admit/retain | `0.35` / `0.25` | Global default/profile override |
| Subject lexical compatibility | `0.20` | Global default/profile override |
| Split sources/tokens | `80` / `16000` | Global default/profile override |
| Merge overlap/cosine/lexical | `0.80` / `0.90` / `0.60` | Global default/profile override |
| Structural operations/sweep | `10` | Global |

Validate positive limits and similarity ranges; retention cannot exceed admission thresholds.
Version assignment, selection, and prompts independently. Assignment-policy changes reconcile
structure, never automatically regenerate prose.

Measure projection lag, eligible/assigned/unassigned coverage, pending/failed work, splits/merges,
page-ID churn, checks versus changed fingerprints, cache hits, first-open latency, retries, and actual
embedding/synthesis usage. Model downtime must not disable directory or lexical search.

## 11. Implementation sequence and acceptance

1. Persistence/capture: migration, typed store operations, transactional feed, deletion/forgetting
   guards, metadata-only auditing, resumable bootstrap.
2. Projection/discovery: deterministic descriptors, semantic assignment, automatic splits/merges,
   directory, coverage, search, temporal boundary scheduling.
3. Articles: bundles, fingerprints, durable jobs, prompt/validation, reuse, conditional publication.
4. Reader/operations: layout/navigation, configuration, explicit refresh/retry, diagnostics, end-to-end
   validation. Do not expose generation before forgetting/publication guards exist.

All four stages constitute the first release. Native compilation/distribution/deployment or changes
to installed binaries are not needed for this work.

### Required automated scenarios

- Fresh/V1 migration preserves memories; bootstrap resumes and catches concurrent changes without
  publishing mismatched structure/search. Failed events prevent misleading checkpoint advancement.
- Worker/job restart, lease recovery, stale fences, late completions, and profile deletion during
  inference cannot publish unauthorized or older versions.
- Discover ungenerated topics through evidence text, disconnected memories, tasks, missing embeddings,
  and live evidence during projection lag. Lexical reads make zero model calls; semantic search
  makes no chat calls and degrades on embedding failure. Generated state has no ranking advantage.
- Fixed fixtures have stable ordering/membership. Hubs do not group unrelated subjects; hysteresis
  prevents churn. Split/merge/rename preserves links; cycles, ID reuse, and structural oscillation
  fail. Distinct entity identities never merge.
- Opening one page generates no neighbors; concurrent opens share a job; unchanged input reuses a
  version. Fingerprint fixed vectors distinguish real changes while ignoring unrelated timestamps.
- Relevant uncited additions and corrections trigger checking/staleness; unrelated changes retain
  reuse. Equivalent reselection clears a check without synthesis.
- Future starts/override expiry change freshness without ingestion. Lag never falsely reports current;
  historical cutoffs are rejected rather than serving current data silently.
- Invalid citations/links/schema, oversized input, sparse evidence, provider failure, and refresh
  failure leave source browsing usable. Changed inputs during inference obsolete the result.
- Forgetting during inference/lag suppresses bodies, older versions, metadata, FTS, and bundles;
  recovery cannot resurrect them or restore competing assertions. Audit/task/log paths retain no
  text bypassing guards. Search, jobs, citations, redirects, and caches enforce profile boundaries.
- Reader renders safely, resolves citations, supports history/responsive/keyboard navigation, and
  discards late responses after profile changes. Hover/prefetch/directory opening never evaluates;
  stale refresh is explicit and failed/evidence-only states remain usable.

Use fake models and deterministic clocks/snapshots; no Ollama/provider network. Follow `*Tests`
naming, favor narrower unit/storage/controller tests, and run `./gradlew test` before committing.
Run existing frontend checks and targeted browser checks for stage 4. Documentation-only edits do
not require builds or deployment.

Before release, compare semantic organization against a key/entity baseline on sparse, code-heavy,
and conversation-heavy fixtures. Review findability, page churn, missed updates, unsupported claims,
lag, reuse, and total inference cost across updates and reading sessions. Human grounding/navigation
review complements deterministic tests. Thresholds are initial defaults, not measured guarantees;
changes update the policy version.

## Historical note: standing-summary primer remains superseded

The old Phase 15 standing-summary follow-up was superseded on 2026-08-05 and remains out of scope.
Session start emits `MemoryPointer`; task-aware recall pulls content after the user's task is known.
Wiki work must not restore session-start content injection. See [feature #12](../POTENTIAL_FEATURES.md)
and [optimization #15](../OPTIMIZATIONS.md) for the rationale.
