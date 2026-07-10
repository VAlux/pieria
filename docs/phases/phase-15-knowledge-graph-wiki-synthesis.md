# Phase 15 - Knowledge-Graph Wiki Synthesis

Status: pending.

## Objective

Synthesize a human-readable, cross-linked **project wiki** from the onboarded entity-relation graph
(Phase 8), the source-code index (Phase 13), and the code narrative summaries (Phase 14). Each graph
entity of sufficient prominence becomes a wiki page; each active edge becomes an inter-page link and
a sourced sentence; the provenance `memory_id` behind every edge makes each claim traceable rather
than model-invented. The output is a cached, regenerated-on-ingest document a human reads to learn
the project — not an on-demand recall response.

Hard dependency on Phase 8 (entity/edge tables, `graphSnapshot`) for the graph; soft dependency on
Phases 13-14 (code index + narrative summaries) to enrich `file`/`concept` pages with architecture
context. Follows the batch-synthesis pattern established by Phase 14's `CodeSummarizationService`.

## Scope

- A new `WikiSynthesisService` that composes pages from `MemoryStore.graphSnapshot(profileId)` plus
  the provenance memories behind the edges, using the existing large (synthesis) model tier through
  `ModelGateway`. No new model tier.
- Content-addressed caching keyed on the graph-snapshot hash so an unchanged graph produces **zero
  model calls**, mirroring Phase 14's payload-hash skip and keyed supersession.
- Persistence of generated pages behind `MemoryStore` (new `wiki_pages` table, or keyed `fact`
  memories with topicKey `wiki:page:<entityId>` reusing the summary machinery — decided in step 2).
- REST surface: `GET /v1/profiles/{name}/wiki` (index + pages) and `GET /v1/profiles/{name}/wiki/{pageId}`,
  alongside the existing `GET /v1/profiles/{name}/graph`.
- Generation runs inside the existing async index/ingest task path, best-effort, never blocking or
  failing ingestion or recall.
- No new retrieval channel and no synthesis-prompt redesign for recall — the wiki is a separate
  read-only artifact built on the existing graph read side.
- No live editing, no HTML renderer redesign beyond serving Markdown/JSON; a viewer in the web
  console (`static/`) is optional follow-up, not in scope here.
- Postgres parity for any new table is logically mirrored only and deferred to Phase 6.

## Implementation Sequence

1. Define the wiki domain model.

- `WikiPage`: `entityId` (the page's anchor entity), `title` (normalized entity name), `type`
  (entity type — project | tool | concept | file | person | ...), synthesized `bodyMarkdown`,
  `links` (outbound `[[entityId]]` references derived from edges), and `citations`
  (`memoryId` list backing the prose).
- `Wiki`: ordered `pages` plus an `index` (grouped by entity type, ranked by degree) and the
  `snapshotHash` the wiki was generated from.
- Content-address the wiki by a stable hash over the sorted `(sourceEntityId, relation, targetEntityId,
  memoryId)` tuples of the active snapshot, salted with a `WIKI_PROMPT_VERSION` so prompt changes
  force regeneration. Reuse the existing `Hash.hash128` helper; add fixed-vector tests.

2. Choose and add persistence.

- Prefer a dedicated `wiki_pages` table (profile-scoped, content-addressed page id, `entity_id`,
  `body`, `payload` JSON carrying citations + snapshot hash, `created_at`) with a Flyway migration,
  insert-or-ignore on the content-addressed id. This keeps wiki pages out of the retrieval/embedding
  path entirely (a wiki page is derived prose, not a memory to recall).
- If reuse is chosen instead, store pages as keyed `fact` memories (topicKey `wiki:page:<entityId>`)
  and exclude them from vectorization like other derived artifacts — but the default recommendation
  is the dedicated table to avoid polluting recall.
- Note the mirrored Postgres logical model in the migration; defer the Postgres-specific migration to
  Phase 6.

3. Page selection over the snapshot.

- Load `graphSnapshot(profileId)` (already filters to connected entities and active edges).
- Rank entities by degree (edge count). High-degree `project`/`concept`/`tool` entities become
  top-level pages; the long tail becomes glossary entries or is folded into a parent page under a
  configurable `min-degree` / `max-pages` cap.
- Deterministic tie-breaking (degree, then type priority, then normalized name) so a fixed snapshot
  yields a fixed page set.

4. Per-page synthesis.

- For each selected page entity, gather its incident edges, the neighbor entities, and the provenance
  memories (`memoryContent` snippets already on `GraphSnapshot.Link`; full memory bodies via
  `findMemoriesByEntities` when richer prose is needed).
- When present, enrich `file`/module/`project` pages with the Phase 14 code summaries
  (`code:summary:*`) for the same paths so architecture pages are grounded in code, not only
  conversation.
- Call the synthesis tier with an "encyclopedia section" prompt (loaded through the single prompt
  component introduced in `18647b3`), instructing the model to write from the supplied evidence only
  and to reference neighbors by a stable placeholder that Java rewrites into `[[entityId]]` links.
- Emit citations as the `memoryId` set of the edges/memories fed to the prompt — the model does not
  invent citations.

5. Content-addressed skipping and supersession.

- Before synthesizing, compare the stored page's snapshot-scoped hash; equal means skip (zero model
  calls). When the graph changed, storing the new page supersedes the stale one via the same keyed
  machinery Phase 14 uses.
- A wiki-level hash lets the whole document short-circuit when nothing changed since the last run.

6. Wire generation into the async task path.

- Run wiki synthesis after graph extraction settles, inside the existing async index/ingest task,
  reporting `"wiki"` progress ticks through the same task, gated behind an opt-in config flag
  (`pieria.wiki.enabled=false` by default, since it puts the large model in the write path — same
  posture as Phase 14 summarization). Per-run override on the ingest/onboard request.
- Best-effort at every level: a per-page failure is counted and skipped; the whole stage is wrapped
  so ingestion results are never affected.

7. Expose the read surface.

- `GET /v1/profiles/{name}/wiki` returns the index + page summaries; `GET .../wiki/{pageId}` returns
  a full page (body + links + citations). Add the matching shared DTOs
  (`WikiResponse`, `WikiPageResponse`) next to `GraphResponse`.
- Serve Markdown by default with a JSON envelope; leave HTML rendering to the console follow-up.

8. Diagnostics.

- Report page count, skipped-vs-regenerated counts, and total synthesis latency/tokens through the
  existing usage/diagnostics surface (`model.usage`), consistent with Phase 14.

## Tests

- Unit tests for the wiki/page content-addressed hashes with fixed vectors, and for deterministic
  page selection and tie-breaking over a fixed snapshot.
- Migration test proving `wiki_pages` creation and idempotent re-generation (or, if reuse chosen,
  that wiki memories are excluded from vectorization/recall).
- Service tests with fake model responses proving: pages are composed from the snapshot + provenance
  memories; neighbor references are rewritten into `[[entityId]]` links; citations equal the fed
  `memoryId` set; a per-page synthesis failure leaves the rest of the wiki intact.
- Skip test proving an unchanged snapshot triggers zero model calls, and that a changed graph
  supersedes the stale page.
- Supersession/active-set test proving pages reflect only active (non-superseded) memories.
- Controller tests for `GET .../wiki` and `GET .../wiki/{pageId}` (present, empty-graph, unknown
  page id).
- Run `./gradlew test`.

## Acceptance Criteria

- With an onboarded graph, `GET /v1/profiles/{name}/wiki` returns a cross-linked set of pages whose
  links correspond to real active edges and whose citations trace to real `memory_id`s.
- Regeneration over an unchanged graph performs zero model calls; a changed graph regenerates only
  the affected pages and supersedes the stale ones.
- Wiki generation failure never fails ingestion; wiki pages never enter the recall/embedding path.
- The wiki reflects supersession: superseded memories and edges off superseded memories never appear
  as pages, links, or citations.
- Page set and ordering are deterministic for a fixed snapshot fixture.

## Risks And Follow-Ups

- **Graph quality is the ceiling.** A wiki surfaces extraction noise far more visibly than recall
  does — inconsistent entity naming or spurious edges read as wrong facts on a page. Depends on
  `EntityNormalizer` and benefits directly from Phase 11 (consolidation / node merging); wiki
  generation is a good forcing function for that work.
- **Cost/latency.** One synthesis call per page is much heavier than a single recall; it is a batch
  artifact, opt-in and cached, not on-demand. Bounded parallelism over pages (like
  `maxExtractionConcurrency`) is future work.
- **Prompt-size blowup** on high-degree hub entities; cap neighborhood size fed per page and
  truncate deterministically, mirroring Phase 14's caps.
- **Injectable overview.** The generated project-overview page is the raw material for the
  standing-summary session primer (see *Follow-Up Feature* below and POTENTIAL_FEATURES #12); build
  the overview page here so the primer is a small compaction step on top rather than a duplicate
  synthesis path.
- **Console viewer.** A force-directed / browsable renderer in `static/` is a natural follow-up but
  intentionally out of scope here; this phase ships the synthesized document + API only.
- Postgres parity for `wiki_pages` is deferred to and coordinated with Phase 6 server mode.

## Follow-Up Feature — Standing-Summary Session Primer

Realized immediately after this phase, once a synthesized project-overview page exists. Fulfills
POTENTIAL_FEATURES #12 (rolling user/project profile compaction) on the session-start injection path.

**Problem it fixes.** Session-start injection today fires a *fixed generic recall query*
(`harness/claude-code/session-start.sh` → the shared `recall.sh`, `EVIDENCE` tier). With no task in
hand at session start, semantic similarity to a generic "summarize the project" string surfaces
whichever memories read most like that string — in practice the planning/spec meta-facts — rather
than the active tasks, standing conventions, and recent decisions a fresh agent actually needs. The
retrieval is correct; the query is a poor proxy for "prime a new session."

**The feature.** Maintain one injectable **standing summary** per profile and inject *that* at
session start, instead of an ad-hoc recall:

- Compact the wiki's project-overview page (the high-degree `project` entity page), enriched with the
  Phase 14 `code:summary:*` architecture/module summaries, into a single bounded primer. Extend it
  with the standing context #12 enumerates: architecture map, module responsibilities, public entry
  points, test/build commands, generated-code boundaries, and known risky files.
- Content-address the primer on the same graph-snapshot hash the wiki uses, so it regenerates only
  when the project changes and costs zero model calls otherwise — reusing this phase's skip
  machinery.
- Add a primer read path the SessionStart hook calls directly (e.g. a `RecallMode.PRIMER` or a
  dedicated `GET /v1/profiles/{name}/primer` returning ready-to-inject text), independent of the
  generic recall query. When no standing summary exists yet (graph not onboarded), fall back to the
  current recall so the hook degrades gracefully.
- Keep the standing summary out of the recall/embedding path, matching how wiki pages and code
  summaries are already excluded from injection recall (`CODE_SESSION` / `excludeCodeDerived`
  precedent).

Depends on this phase's project-overview page plus the Phase 14 summaries; coordinate with #12 rather
than duplicating its synthesis.
