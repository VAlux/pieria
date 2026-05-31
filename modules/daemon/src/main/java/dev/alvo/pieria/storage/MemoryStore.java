package dev.alvo.pieria.storage;

import dev.alvo.pieria.domain.graph.Edge;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.ingestion.model.OutboxEntry;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.domain.profile.ProfileCount;
import dev.alvo.pieria.domain.profile.ProfileStats;
import dev.alvo.pieria.retrieval.model.RecallCandidate;

import java.util.List;
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
   * Insert raw conversation messages with content-addressed ids (insert-or-ignore, idempotent).
   */
  void insertMessages(String profileId, String sessionId, List<Message> messages);

  /**
   * Insert a single memory with a content-addressed id (insert-or-ignore). Returns the stored
   * memory with its assigned id and timestamp.
   */
  Memory insertMemory(String profileId, Memory memory);

  /**
   * Ingestion write, all in one transaction:
   * <ol>
   *   <li>For keyed types ({@code fact}/{@code instruction}) with a {@code topic_key}, find the
   *       active memory sharing {@code (profileId, type, topic_key)}; if present, mark it
   *       superseded and point the new row's {@code supersedes} at it, and delete its embedding.</li>
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
   */
  default StoreOutcome store(String profileId, Memory memory, GraphFragment graph) {
    throw new UnsupportedOperationException("store(...) not implemented");
  }

  /**
   * Active (non-superseded) memories sharing {@code (profileId, type, topicKey)}. Empty for null keys.
   */
  default List<Memory> findActiveByTopicKey(String profileId, MemoryType type, String topicKey) {
    throw new UnsupportedOperationException("findActiveByTopicKey(...) not implemented");
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
   * Outcome of {@link #store(String, Memory)}: the stored memory, the id of any memory it
   * superseded (or {@code null}), and whether a vectorization outbox row was enqueued.
   */
  record StoreOutcome(Memory stored, String supersededId, boolean enqueuedVector) {
  }

  // ---- sqlite-vec index + FTS5 retrieval channels ----

  /**
   * Whether embedded vector search is available: the native {@code sqlite-vec} extension loaded
   * AND {@code pieria.retrieval.vector-enabled} is true. When false, the vector channels are
   * no-ops and {@link #vectorSearch} returns an empty list so recall degrades to FTS + keyed.
   */
  default boolean isVectorSearchAvailable() {
    return false;
  }

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
   * Backfill {@code memories_vec} from the {@code embedding} BLOB column for active, vector-eligible
   * memories ({@code superseded = 0} and {@code type != 'task'}) that are missing from the index.
   * Returns the count backfilled. No-op (returns 0) when vector search is unavailable.
   */
  default int backfillVectors() {
    return 0;
  }

  /**
   * List active (non-superseded) memories, optionally filtered by type and/or session.
   * Null filters mean "no filter on that dimension".
   */
  List<Memory> listMemories(String profileId, MemoryType typeFilter, String sessionFilter);

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
}
