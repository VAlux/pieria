# Phase 8 - Graph / Relationship Memory Layer

## Objective

Add an entity-relation graph over the existing memory store: extract entities and relations during ingestion classification, persist them as profile-scoped `entities` and `edges` tables behind `MemoryStore`, and add a `GraphChannel` that traverses the graph alongside the existing five retrieval channels and feeds its results into the existing weighted Reciprocal Rank Fusion. Hard dependency on Phase 2 (ingestion / classification stage) and Phase 3 (retrieval channels + RRF). Postgres graph storage is logically mirrored here but its full backend alignment is deferred to and coordinated with Phase 6 server mode.

## Scope

- Graph extraction inside the Phase 2 classification stage (reuse the small/fast model and structured output through `ModelGateway`; no new model tier).
- New embedded-store tables `entities` and `edges`, profile-scoped, with content-addressed IDs, behind the `MemoryStore` interface.
- One new retrieval channel (`GraphChannel`) and one new `RetrievalChannelType` value, fused via existing weighted RRF.
- No MCP gateway, packaging, or synthesis-prompt redesign work beyond passing graph candidates through the existing fusion path.
- No new domain types for relationships exposed in the public REST DTOs; the graph is an internal retrieval signal in this phase.
- Postgres backend gets the logical schema noted only; full parity is deferred to Phase 6.

## Implementation Sequence

1. Define the graph domain model.
   - `Entity`: content-addressed `id`, `profileId`, normalized `name`, `type` (e.g. person, project, tool, file, concept), and `payload` JSON for attributes.
   - `Edge`: content-addressed `id`, `profileId`, `sourceEntityId`, `targetEntityId`, normalized `relation` label, the originating `memoryId` (provenance), and `createdAt`.
   - Entity ID: SHA-256 over `profileId`, entity `type`, and normalized `name`, truncated to the existing stable width. Edge ID: SHA-256 over `profileId`, `sourceEntityId`, `relation`, `targetEntityId`, and `memoryId`. Reuse the existing ID helper; add fixed-vector unit tests.

2. Add storage schema and migrations.
   - Flyway migration creating `entities` and `edges` with profile scoping and indexes on `(profile_id, name)`, `(profile_id, source_entity_id)`, `(profile_id, target_entity_id)`, and `edges.memory_id`.
   - Insert-or-ignore on content-addressed IDs so re-ingest is idempotent.
   - Edges reference `memories.id`; an edge is active only when its source `memory` row is active (`superseded = 0`). Do not physically delete edges on supersession.
   - Note the mirrored Postgres logical model in the migration; defer the Postgres-specific migration to Phase 6.

3. Extend `MemoryStore` with graph methods (default-throw, consistent with existing seams).
   - `upsertEntity(...)`, `upsertEdge(...)` used inside the ingestion store transaction.
   - `findEntitiesByName(profileId, names, limit)` for seeding from query entities.
   - `neighborhood(profileId, entityIds, depth, limit)` returning active edges and their entities, joined to active source memories, excluding edges whose source memory is superseded.
   - `findMemoriesByEntities(profileId, entityIds, limit)` returning active `Memory` rows reachable from the seed entities via provenance edges, ranked by edge proximity then recency.

4. Extract entities and relations in the classification stage.
   - In the Phase 2 classification/enrichment step, add a structured-output extraction pass (small model via `ModelGateway`) that, for each verified memory, emits entities and `(source, relation, target)` triples grounded in that memory's content.
   - Normalize entity names deterministically in Java (casing, whitespace, simple aliasing) before ID computation; do not let the model invent IDs.
   - Persist entities and edges inside the same ingestion store transaction as the memory insert, tagging each edge with its source `memoryId`.
   - Skip graph extraction for `task` memories unless a triple is explicit; keep extraction additive to existing classification, not a replacement.

5. Keep graph extraction degradable.
   - Graph extraction failure (model error, parse error, empty output) must log and continue; it must never fail ingestion or roll back the memory write.
   - Bound extraction with the same concurrency/limit settings used by the rest of the classification stage.
   - Deduplicate triples by normalized `(source, relation, target)` before persistence.

6. Implement `GraphChannel`.
   - Seed entities from (a) entities named in the query analysis and (b) entities attached to top candidates already surfaced by the vector/FTS channels for this query.
   - Expand the neighborhood to a configurable depth (default 1-2 hops) and bounded fan-out, then collect active memories reachable via provenance edges.
   - Emit ranked `RetrievalCandidate` values carrying the new channel type and edge-path provenance snippets, ordered by graph proximity then recency.
   - Exclude memories whose row is superseded and edges whose source memory is superseded so supersession is respected in active queries.

7. Wire `GraphChannel` into the fan-out and fusion.
   - Add `GRAPH` to `RetrievalChannelType`.
   - Add `GraphChannel` to the channel list in `RetrievalService` so it runs inside the existing `StructuredTaskScope`/virtual-thread fan-out, bounded by the existing per-channel timeout and limit.
   - Mark the channel non-critical: its failure or timeout is logged and contributes nothing, never failing recall.
   - Add a configurable RRF weight `weightGraph` and register it in the channel-weights map; default it as a primary-tier signal below `EXACT_KEY` and comparable to the FTS/vector channels, kept tunable for Phase 5 evaluation.

8. Add graph diagnostics.
   - Report `GRAPH` per-channel latency, hit count, and failure flag through the existing diagnostics surface.
   - Expose seed-entity count and expanded-neighborhood size only under the existing debug flag.

## Tests

- Unit tests for entity and edge content-addressed IDs with fixed vectors, and for deterministic name normalization.
- Migration tests proving `entities`/`edges` creation, indexes, and idempotent re-ingest.
- Service tests using fake model responses proving entities and edges are persisted in the ingestion transaction, and that an extraction failure leaves the memory write committed.
- Storage tests for `neighborhood` and `findMemoriesByEntities` proving superseded memories and edges off superseded source memories do not surface.
- Channel tests for seeding (query entities + surfaced candidates), depth-bounded expansion, and ranked candidate output with provenance.
- Fusion tests proving graph candidates participate in weighted RRF deterministically and that graph-channel failure yields partial results without failing recall.
- Run `./gradlew test`.

## Acceptance Criteria

- Ingestion persists entities and edges grounded in verified memories, idempotently and within the existing store transaction.
- Graph extraction failure never fails ingestion; graph channel failure never fails recall.
- `GraphChannel` runs alongside the existing five channels and feeds the existing weighted RRF via a configurable `weightGraph`.
- Edges off superseded memories and superseded memories themselves never appear in active query results.
- Fused rankings including the graph channel are deterministic for a fixed fixture.

## Risks And Follow-Ups

- Small-model triple extraction quality varies; keep prompts versioned for deterministic fake-model tests and the channel easy to disable for comparison.
- Neighborhood expansion can fan out combinatorially; depth and fan-out must stay bounded and configurable, with the graph weight treated as a default until Phase 5 evaluation data exists.
- Entity resolution / aliasing is intentionally minimal here; richer node merging is owned by Phase 11 (Memory Consolidation & Reflection), which consolidates graph nodes and edges built in this phase.
- Phase 12 (Execution-Trace Memory) builds on this layer by linking tool-output entities into this graph; keep the `Edge.memoryId` provenance seam stable for that consumer.
- Postgres graph storage parity (pgvector-adjacent schema and recursive neighborhood queries) is deferred to and coordinated with Phase 6 server mode.
