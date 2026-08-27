package dev.alvo.pieria.storage;

import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.graph.Edge;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.graph.GraphCounts;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.graph.IncidentEdge;
import dev.alvo.pieria.domain.graph.NeighborHop;
import dev.alvo.pieria.domain.graph.RankedEntity;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.domain.profile.ProfileCount;
import dev.alvo.pieria.domain.profile.ProfileStats;
import dev.alvo.pieria.domain.profile.ProfileUsage;
import dev.alvo.pieria.ingestion.model.OutboxEntry;
import dev.alvo.pieria.model.usage.InferenceTier;
import dev.alvo.pieria.model.usage.TierUsage;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.tools.Tokens;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The single persistence seam behind all storage backends.
 * All ingestion writes and retrieval channels are defined against this interface.
 */
public interface MemoryStore {

  /**
   * Look up a profile by name, creating it if absent.
   */
  Profile getOrCreateProfile(String name);

  /**
   * Create a brand-new, empty profile with this name. Unlike {@link #getOrCreateProfile}, this is
   * <em>not</em> idempotent: if a profile with that name already exists it throws
   * {@link dev.alvo.pieria.domain.error.ConflictException} rather than returning the existing one.
   */
  default Profile createProfile(String name) {
    findProfile(name).ifPresent(existing -> {
      throw dev.alvo.pieria.domain.error.ConflictException.profileExists(name);
    });
    return getOrCreateProfile(name);
  }

  /**
   * Permanently delete a profile and everything owned by it — memories, raw messages, the
   * entity-relation graph, the code index, per-profile config and usage counters, and any vector
   * index rows. Unlike memory {@code forget} (logical supersession), this is a hard, irreversible
   * physical delete. No-op for a profile id that does not exist.
   */
  default void deleteProfile(String profileId) {
    throw new UnsupportedOperationException("deleteProfile(...) not implemented");
  }

  /**
   * Find a profile by name, or {@code null} if it does not exist.
   */
  Optional<Profile> findProfile(String name);

  /**
   * All profiles with their active (non-superseded) memory counts, ordered by name.
   */
  default List<ProfileCount> listProfiles() {
    throw new UnsupportedOperationException("listProfiles() not implemented");
  }

  /**
   * Aggregate counts over a single profile's memories (active totals, by-type breakdown,
   * superseded count, distinct active sessions, and the active createdAt range).
   */
  default ProfileStats profileStats(String profileId) {
    throw new UnsupportedOperationException("profileStats(...) not implemented");
  }

  /**
   * Accumulate one recall into the profile's lifetime savings counters: bump {@code recall_count}
   * and add {@code max(0, sourceTokens − answerTokens)} — the cost avoided by not re-reading the
   * source material behind the evidence. Best-effort and accounting-only — a no-op for backends
   * that do not track usage.
   */
  default void recordRecallUsage(String profileId, long sourceTokens, long answerTokens) {
  }

  /**
   * Σ {@code source_tokens} over the active memories among {@code memoryIds} within this profile —
   * the raw source material behind a recall's evidence. Duplicate ids are counted once. Returns
   * {@code 0} for an empty id set or a backend that does not track provenance.
   */
  default long sumActiveSourceTokens(String profileId, Collection<String> memoryIds) {
    return 0L;
  }

  /**
   * Accumulate one ingest into the profile's lifetime savings counters: bump {@code ingest_count}
   * and add the raw-message and distilled-memory token totals. Best-effort and accounting-only — a
   * no-op for backends that do not track usage.
   */
  default void recordIngestUsage(String profileId, long ingestedTokens, long storedTokens) {
  }

  /**
   * The profile's lifetime savings counters, or {@link ProfileUsage#empty()} when the backend does
   * not track usage or the profile has no row yet.
   */
  default ProfileUsage usageStats(String profileId) {
    return ProfileUsage.empty();
  }

  /**
   * Accumulate one operation's real provider token usage into the profile's lifetime inference-spend
   * counters, per {@link InferenceTier}. {@code usage} is a per-tier snapshot of an
   * {@code InferenceUsageAccumulator}; an empty map is a no-op. Best-effort and accounting-only — a
   * no-op for backends that do not track spend.
   */
  default void recordInferenceUsage(String profileId, Map<InferenceTier, TierUsage> usage) {
  }

  /**
   * The profile's lifetime inference-spend counters keyed by tier, or an empty map when the backend
   * does not track spend or the profile has no rows yet.
   */
  default Map<InferenceTier, TierUsage> inferenceUsage(String profileId) {
    return Map.of();
  }

  /**
   * Insert raw conversation messages with profile-scoped content-addressed ids (insert-or-ignore,
   * idempotent within one profile).
   */
  void insertMessages(String profileId, String sessionId, List<Message> messages);

  /**
   * Insert a single memory with a profile-scoped content-addressed id (insert-or-ignore). Returns
   * the stored memory with its assigned id and timestamp.
   */
  Memory insertMemory(String profileId, Memory memory);

  /**
   * Ingestion write, all in one transaction:
   * <ol>
   *   <li>For keyed types ({@code fact}/{@code instruction}) with a {@code topic_key}, find the
   *       active memory sharing {@code (profileId, type, topic_key)}; if present, mark it
   *       superseded and point the new row's {@code supersedes} at it, and delete its embedding.</li>
   *   <li>Failing that, find an active {@code fact}/{@code instruction} whose content is a near
   *       duplicate of the incoming one and supersede that instead — the extractor invents a fresh
   *       {@code topic_key} per run, so the same fact restated next session would otherwise
   *       accumulate rather than supersede.</li>
   *   <li>Insert the new memory (insert-or-ignore on content-addressed id).</li>
   *   <li>Enqueue a vectorization outbox row unless the type is {@code task} (tasks are not embedded).</li>
   * </ol>
   * Returns the outcome describing the stored row and any superseded predecessor.
   */
  default StoreOutcome store(String profileId, Memory memory) {
    return store(profileId, memory, GraphFragment.empty());
  }

  /**
   * Ingestion write with an attached graph fragment, all in one transaction: the same memory
   * insert/supersession/outbox steps as {@link #store(String, Memory)}, plus persisting the
   * fragment's entities and edges (each edge tagged with the stored memory's id as provenance).
   * The graph extraction model call must happen <em>before</em> this method so no model I/O occurs
   * inside the transaction. An {@link GraphFragment#empty()} fragment makes this equivalent to the
   * two-arg form.
   *
   * <p>Source tokens default to the memory's own content: a memory written without ingest
   * provenance (the explicit {@code remember} path) was never distilled from anything larger, so
   * recalling it saves nothing.
   */
  default StoreOutcome store(String profileId, Memory memory, GraphFragment graph) {
    return store(profileId, memory, graph, Tokens.estimate(memory.content()));
  }

  /**
   * As {@link #store(String, Memory, GraphFragment)}, recording {@code sourceTokens} — the raw
   * source material this memory was distilled from — on the stored row. Ingest passes each
   * memory's proportional slice of its source chunk; the savings figure reported by
   * {@link #recordRecallUsage} is computed against these totals.
   */
  default StoreOutcome store(String profileId, Memory memory, GraphFragment graph, long sourceTokens) {
    throw new UnsupportedOperationException("store(...) not implemented");
  }

  /**
   * Active (non-superseded) memories sharing {@code (profileId, type, topicKey)}. Empty for null keys.
   */
  default List<Memory> findActiveByTopicKey(String profileId, MemoryType type, String topicKey) {
    throw new UnsupportedOperationException("findActiveByTopicKey(...) not implemented");
  }

  /**
   * Blocking step for near-duplicate detection: active memories of {@code type} in
   * {@code profileId} whose content shares vocabulary with {@code content}, best FTS match first
   * and capped at {@code limit}. These are <em>candidates</em> — the caller scores them and decides
   * what counts as a duplicate. Comparing against every memory in the profile would not scale, and
   * a near-identical restatement always shares most of its content words, so the FTS ranking is a
   * sound filter.
   */
  default List<Memory> findNearDuplicateCandidates(
    String profileId, MemoryType type, String content, int limit) {
    throw new UnsupportedOperationException("findNearDuplicateCandidates(...) not implemented");
  }

  /**
   * Look up a single memory by id across the whole store (the outbox is profile-agnostic; the
   * vectorization worker needs {@code embed_text} by memory id).
   */
  default Optional<Memory> findMemoryById(String memoryId) {
    throw new UnsupportedOperationException("findMemoryById(...) not implemented");
  }

  /**
   * Drain up to {@code batchSize} pending outbox rows (oldest first) for the vectorization worker.
   */
  default List<OutboxEntry> drainOutbox(int batchSize) {
    throw new UnsupportedOperationException("drainOutbox(...) not implemented");
  }

  /**
   * Record a failed vectorization attempt: increment {@code attempts} and log {@code lastError}.
   */
  default void recordOutboxFailure(String memoryId, String lastError) {
    throw new UnsupportedOperationException("recordOutboxFailure(...) not implemented");
  }

  /**
   * Drop an outbox row without persisting an embedding (used to abandon a poison message that has
   * exhausted its retries, or an orphaned row whose memory is gone).
   */
  default void deleteOutboxRow(String memoryId) {
    throw new UnsupportedOperationException("deleteOutboxRow(...) not implemented");
  }

  /**
   * Persist an embedding for a memory and delete its outbox row, in one transaction (the outbox
   * row is removed only after the embedding write commits).  stores the raw vector on the
   * memory row;  builds the {@code sqlite-vec} index from it.
   */
  default void completeVectorization(String memoryId, float[] embedding) {
    throw new UnsupportedOperationException("completeVectorization(...) not implemented");
  }

  /**
   * Current vectorization-outbox depth when the backend can report it. Used only for local status;
   * backends that do not expose an outbox can leave this empty.
   */
  default OptionalLong vectorizationOutboxDepth() {
    return OptionalLong.empty();
  }

  /**
   * Whether embedded vector search is available: the native {@code sqlite-vec} extension loaded
   * AND {@code pieria.retrieval.vector-enabled} is true. When false, the vector channels are
   * no-ops and {@link #vectorSearch} returns an empty list so recall degrades to FTS + keyed.
   */
  default boolean isVectorSearchAvailable() {
    return false;
  }

  // ---- sqlite-vec index + FTS5 retrieval channels ----

  /**
   * Upsert an embedding into the {@code memories_vec} index for {@code memoryId}. No-op when vector
   * search is unavailable. Tasks must never be passed here (they are excluded from the index).
   */
  default void upsertEmbedding(String memoryId, float[] embedding) {
    throw new UnsupportedOperationException("upsertEmbedding(...) not implemented");
  }

  /**
   * Delete the {@code memories_vec} row for {@code memoryId} (used on supersession/forget). No-op
   * when vector search is unavailable.
   */
  default void deleteEmbedding(String memoryId) {
    throw new UnsupportedOperationException("deleteEmbedding(...) not implemented");
  }

  /**
   * Memory FTS channel: FTS5 MATCH over {@code memories_fts}, joined to {@code memories},
   * filtered to {@code profileId} and the active set ({@code superseded = 0}), ordered by FTS rank
   * (best first). The query is sanitized so arbitrary user text cannot raise an FTS5 syntax error.
   */
  default List<Memory> searchMemoriesFts(String profileId, String matchQuery, int limit) {
    throw new UnsupportedOperationException("searchMemoriesFts(...) not implemented");
  }

  /**
   * Raw-message FTS safety net: FTS5 MATCH over {@code messages_fts} for {@code profileId};
   * returns ACTIVE memories whose {@code session_id} is among the matched messages' sessions, ranked
   * by message relevance then recency.
   */
  default List<Memory> searchMemoriesByMessageFts(String profileId, String matchQuery, int limit) {
    throw new UnsupportedOperationException("searchMemoriesByMessageFts(...) not implemented");
  }

  /**
   * Exact topic-key channel: active {@code fact}/{@code instruction} memories whose
   * {@code topic_key} is in {@code topicKeys}, ordered by the position of the key in the input list
   * (priority) then {@code created_at} desc.
   */
  default List<Memory> exactKeyLookup(String profileId, List<String> topicKeys, int limit) {
    throw new UnsupportedOperationException("exactKeyLookup(...) not implemented");
  }

  /**
   * Direct/HyDE vector channel: KNN over {@code memories_vec} joined to {@code memories},
   * filtered to {@code profileId}, the active set, and excluding {@code task} (defensive), ordered by
   * distance ascending. Returns an empty list when vector search is unavailable.
   */
  default List<Memory> vectorSearch(String profileId, float[] queryEmbedding, int limit) {
    throw new UnsupportedOperationException("vectorSearch(...) not implemented");
  }

  /**
   * The stored embeddings for {@code memoryIds} within {@code profileId}, keyed by memory id.
   *
   * <p>Reads the {@code embedding} BLOB column directly rather than the {@code memories_vec} index,
   * so it works whether or not vector search is available. Ids with no embedding — never vectorized,
   * still queued in the outbox, or {@code task} type, which is excluded from the index by design —
   * are simply absent from the result rather than mapped to null. Callers must therefore treat a
   * missing id as "cannot compare", not as "not similar".
   */
  default Map<String, float[]> embeddingsFor(String profileId, Collection<String> memoryIds) {
    return Map.of();
  }

  /**
   * Backfill {@code memories_vec} from the {@code embedding} BLOB column for active, vector-eligible
   * memories ({@code superseded = 0} and {@code type != 'task'}) that are missing from the index.
   * Returns the count backfilled. No-op (returns 0) when vector search is unavailable.
   */
  default int backfillVectors() {
    return 0;
  }

  /**
   * Upsert the per-profile config overrides as canonical JSON (one row per profile, replaced
   * wholesale on each push from the CLI).
   */
  default void putProfileConfig(String profileId, String configJson) {
    throw new UnsupportedOperationException("putProfileConfig(...) not implemented");
  }

  /**
   * The stored per-profile config overrides JSON, or empty when the profile has none.
   */
  default Optional<String> getProfileConfig(String profileId) {
    throw new UnsupportedOperationException("getProfileConfig(...) not implemented");
  }

  /**
   * Remove the per-profile config overrides (the profile falls back to the global config).
   */
  default void clearProfileConfig(String profileId) {
    throw new UnsupportedOperationException("clearProfileConfig(...) not implemented");
  }

  /**
   * The incremental-onboarding ledger for one {@code (profile, scope)}: content hashes keyed by
   * document provenance. A document whose current hash matches its ledger entry was fully
   * processed by a previous onboard and can skip the model pipeline entirely. Empty when nothing
   * was onboarded yet.
   */
  default Map<String, String> ingestLedger(String profileId, String scope) {
    throw new UnsupportedOperationException("ingestLedger(...) not implemented");
  }

  /**
   * Upsert ledger entries for documents whose memories are now durably stored ({@code hashesByKey}:
   * content hash by document provenance). Callers write per completed batch — never ahead of the
   * store — so the ledger only ever claims work that actually finished.
   */
  default void recordIngestLedger(String profileId, String scope, Map<String, String> hashesByKey) {
    throw new UnsupportedOperationException("recordIngestLedger(...) not implemented");
  }

  /**
   * List active (non-superseded) memories, optionally filtered by type and/or session.
   * Null filters mean "no filter on that dimension". Convenience overload of
   * {@link #listMemories(String, MemoryType, String, boolean)} with {@code includeSuperseded=false}.
   */
  default List<Memory> listMemories(String profileId, MemoryType typeFilter, String sessionFilter) {
    return listMemories(profileId, typeFilter, sessionFilter, false);
  }

  /**
   * List memories, optionally filtered by type and/or session. Null filters mean "no filter on
   * that dimension". When {@code includeSuperseded} is false, only active (non-superseded)
   * memories are returned; when true, superseded memories are included as well. Ordered newest
   * first ({@code created_at} desc).
   */
  List<Memory> listMemories(String profileId, MemoryType typeFilter, String sessionFilter, boolean includeSuperseded);

  /**
   * Mark a memory superseded (logical delete); never physically deletes.
   * Returns {@code true} if a matching active memory was found and updated.
   */
  boolean forgetMemory(String profileId, String memoryId);

  /**
   * Export all memories for a profile as NDJSON-friendly rows.
   */
  List<ExportRow> exportProfile(String profileId);

  /**
   * Retrieval: keyed + lexical (LIKE) lookup over active memories and message text.
   * Shaped so additional channels can be added without changing callers.
   */
  List<RecallCandidate> findRecallCandidates(String profileId, String query, int limit);

  /**
   * Upsert a graph entity (insert-or-ignore on its content-addressed id). The id and createdAt are
   * computed when null. Returns the stored entity with its assigned id/timestamp.
   */
  default Entity upsertEntity(String profileId, Entity entity) {
    throw new UnsupportedOperationException("upsertEntity(...) not implemented");
  }

  /**
   * Upsert a graph edge (insert-or-ignore on its content-addressed id). The id and createdAt are
   * computed when null. Returns the stored edge with its assigned id/timestamp.
   */
  default Edge upsertEdge(String profileId, Edge edge) {
    throw new UnsupportedOperationException("upsertEdge(...) not implemented");
  }

  /**
   * Seed entities by normalized name: entities in {@code profileId} whose {@code name} is in
   * {@code names}, capped at {@code limit}. Used to seed the graph channel from query entities.
   */
  default List<Entity> findEntitiesByName(String profileId, List<String> names, int limit) {
    throw new UnsupportedOperationException("findEntitiesByName(...) not implemented");
  }

  /**
   * Entities appearing on edges whose {@code memory_id} is in {@code memoryIds} and whose source
   * memory is active. Used to seed the graph channel from wave-1 candidate memories.
   */
  default List<Entity> entitiesForMemories(String profileId, List<String> memoryIds, int limit) {
    throw new UnsupportedOperationException("entitiesForMemories(...) not implemented");
  }

  /**
   * Expand the active-edge neighborhood of {@code seedEntityIds} up to {@code depth} hops, bounded
   * by {@code fanout} newly-discovered entities per hop. Returns the reachable entity ids in BFS
   * order (seeds first), deduped. Only edges off active (non-superseded) memories are traversed.
   */
  default List<String> neighborhood(String profileId, List<String> seedEntityIds, int depth, int fanout) {
    throw new UnsupportedOperationException("neighborhood(...) not implemented");
  }

  /**
   * Active memories reachable from {@code entityIds} via provenance edges (an edge touches an
   * entity as source or target), ranked by proximity (earliest-listed touching entity wins) then
   * recency, deduped, capped at {@code limit}. Superseded memories never surface.
   */
  default List<Memory> findMemoriesByEntities(String profileId, List<String> entityIds, int limit) {
    throw new UnsupportedOperationException("findMemoriesByEntities(...) not implemented");
  }

  /**
   * Active code-derived memories whose {@code payload.symbolIds} array intersects {@code symbolIds}.
   * This is the provenance link the Phase 13 code channels use to resolve a symbol/edge hit back to
   * the {@code Memory} retrieval unit. Ordered most-recent first, deduped, capped at {@code limit}.
   */
  default List<Memory> findCodeMemoriesBySymbolIds(String profileId, List<String> symbolIds, int limit) {
    throw new UnsupportedOperationException("findCodeMemoriesBySymbolIds(...) not implemented");
  }

  /**
   * Profile-wide graph totals for the explorer's status line: connected entities and active edges.
   *
   * <p>Every read below shares one definition of "active": the edge's provenance memory is not
   * superseded. Entities reachable only through superseded edges are invisible to all of them.
   */
  default GraphCounts graphCounts(String profileId) {
    throw new UnsupportedOperationException("graphCounts(...) not implemented");
  }

  /**
   * Entity counts per normalized type across the whole profile, highest count first. Drives the
   * explorer's type facet, so it counts every entity — including ones no active edge touches.
   */
  default Map<String, Integer> entityTypeCounts(String profileId) {
    throw new UnsupportedOperationException("entityTypeCounts(...) not implemented");
  }

  /**
   * The profile's hubs: entities with the highest active-edge degree, capped at {@code limit}.
   * When {@code types} is non-empty only entities of those types are considered. This is the
   * explorer's landing set — the whole graph is never returned.
   */
  default List<RankedEntity> topEntitiesByDegree(String profileId, List<String> types, int limit) {
    throw new UnsupportedOperationException("topEntitiesByDegree(...) not implemented");
  }

  /**
   * Entity name search for the explorer's search box: case-insensitive substring match over
   * normalized names, ranked by degree, optionally narrowed to {@code types}, capped at
   * {@code limit}. Unlike {@link #findEntitiesByName} (exact names, retrieval seeding) this is a
   * human-facing lookup.
   */
  default List<RankedEntity> searchEntities(String profileId, String query, List<String> types, int limit) {
    throw new UnsupportedOperationException("searchEntities(...) not implemented");
  }

  /**
   * Bounded breadth-first walk out from {@code seedEntityId} over active edges, up to {@code depth}
   * hops and at most {@code fanout} newly-discovered entities per hop, optionally restricted to
   * {@code types}. Returns the seed (hop {@code 0}) followed by everything reached, in BFS order.
   *
   * <p>Distinct from {@link #neighborhood} — that one seeds from many entities and returns bare ids
   * for the retrieval graph channel; this one carries hop distance and a type filter for the viewer.
   */
  default List<NeighborHop> graphNeighborhood(String profileId, String seedEntityId, int depth,
                                              List<String> types, int fanout) {
    throw new UnsupportedOperationException("graphNeighborhood(...) not implemented");
  }

  /**
   * Active edges with <em>both</em> endpoints inside {@code entityIds} — the edges the viewer can
   * actually draw once its node set is fixed. Edges leaving the set are omitted rather than drawn
   * as dangling stubs.
   */
  default List<Edge> inducedEdges(String profileId, List<String> entityIds) {
    throw new UnsupportedOperationException("inducedEdges(...) not implemented");
  }

  /**
   * Hydrate entities by id, profile-scoped. Ids not present in the profile are silently skipped.
   */
  default List<Entity> findEntitiesByIds(String profileId, List<String> entityIds) {
    throw new UnsupportedOperationException("findEntitiesByIds(...) not implemented");
  }

  /**
   * Active-edge degree per entity id. Ids with no active edge are absent from the map rather than
   * mapped to zero.
   */
  default Map<String, Integer> entityDegrees(String profileId, List<String> entityIds) {
    throw new UnsupportedOperationException("entityDegrees(...) not implemented");
  }

  /**
   * Every active edge touching {@code entityId}, in either direction, with the entity at the far end
   * resolved, newest first, capped at {@code limit}. Backs the inspector's relation list.
   */
  default List<IncidentEdge> incidentEdges(String profileId, String entityId, int limit) {
    throw new UnsupportedOperationException("incidentEdges(...) not implemented");
  }

  /**
   * Active, non-{@code TASK} memories that carry no graph edges and have not yet been through orphan
   * adoption ({@code graph_adopted_at IS NULL}), oldest first, capped at {@code limit}. These are the
   * "orphans" the reminiscence task retroactively weaves into the graph (typically {@code remember}-
   * authored memories, which are stored with an empty fragment). See
   * {@link dev.alvo.pieria.reminiscence.ReminiscenceService}.
   */
  default List<Memory> findGraphOrphans(String profileId, int limit) {
    throw new UnsupportedOperationException("findGraphOrphans(...) not implemented");
  }

  /**
   * Count of the same set {@link #findGraphOrphans} returns, for a cheap dry-run and a stable
   * progress total.
   */
  default long countGraphOrphans(String profileId) {
    throw new UnsupportedOperationException("countGraphOrphans(...) not implemented");
  }

  /**
   * Session-scoped orphan page used by automatic onboarding enrichment.
   */
  default List<Memory> findGraphOrphans(String profileId, List<String> sessionIds, int limit) {
    throw new UnsupportedOperationException("findGraphOrphans(..., sessions, ...) not implemented");
  }

  /**
   * Count of session-scoped orphans used by automatic onboarding enrichment.
   */
  default long countGraphOrphans(String profileId, List<String> sessionIds) {
    throw new UnsupportedOperationException("countGraphOrphans(..., sessions) not implemented");
  }

  /**
   * Retroactively attach an already-extracted fragment to an existing memory, then stamp
   * {@code graph_adopted_at} so a genuinely-edgeless memory is never re-extracted. No re-store of the
   * memory occurs (so no {@code topic_key} supersession is re-triggered and no vectorization is
   * re-enqueued). Idempotent: entity/edge upserts are insert-or-ignore, and an empty fragment only
   * stamps the marker. Runs in one transaction.
   */
  default void attachGraph(String profileId, String memoryId, GraphFragment graph) {
    throw new UnsupportedOperationException("attachGraph(...) not implemented");
  }

  /**
   * Stamp {@code graph_adopted_at} without attaching a fragment — "this memory's graph is already
   * settled; never send it through orphan adoption". Used by the code indexer, which derives its
   * memories' graph deterministically from the parse and so must never pay for a model call over
   * machine-generated template text. Idempotent.
   */
  default void markGraphAdopted(String profileId, String memoryId) {
    throw new UnsupportedOperationException("markGraphAdopted(...) not implemented");
  }

  /**
   * Whether {@code memoryId} has been through graph adoption ({@code graph_adopted_at IS NOT NULL}).
   * Lets the code indexer detect derived memories stored before deterministic projection existed and
   * repair them on the next index pass. {@code false} when the memory does not exist.
   */
  default boolean isGraphAdopted(String profileId, String memoryId) {
    throw new UnsupportedOperationException("isGraphAdopted(...) not implemented");
  }

  /**
   * Outcome of {@link #store(String, Memory)}: the stored memory, the id of any memory it
   * superseded (or {@code null}), whether a vectorization outbox row was enqueued, and whether this
   * call inserted the memory row rather than reusing an idempotent existing row.
   */
  record StoreOutcome(Memory stored, String supersededId, boolean enqueuedVector, boolean inserted) {
  }
}
