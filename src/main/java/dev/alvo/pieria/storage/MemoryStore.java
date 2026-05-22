package dev.alvo.pieria.storage;

import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.OutboxEntry;
import dev.alvo.pieria.domain.Profile;
import dev.alvo.pieria.domain.RecallCandidate;

import java.util.List;
import java.util.Optional;

/**
 * The single persistence seam behind both storage backends (SPEC 5.4). Phase 1 ships only the
 * embedded SQLite implementation; the Postgres backend (Phase 6) implements the same contract.
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
   * Insert raw conversation messages with content-addressed ids (insert-or-ignore, idempotent).
   */
  void insertMessages(String profileId, String sessionId, List<Message> messages);

  /**
   * Insert a single memory with a content-addressed id (insert-or-ignore). Returns the stored
   * memory with its assigned id and timestamp.
   */
  Memory insertMemory(String profileId, Memory memory);

  /**
   * Phase 2 ingestion write (SPEC 5.6, 6.5-6.7), all in one transaction:
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
   * row is removed only after the embedding write commits). Phase 2 stores the raw vector on the
   * memory row; Phase 3 builds the {@code sqlite-vec} index from it.
   */
  default void completeVectorization(String memoryId, float[] embedding) {
    throw new UnsupportedOperationException("completeVectorization(...) not implemented");
  }

  /**
   * Outcome of {@link #store(String, Memory)}: the stored memory, the id of any memory it
   * superseded (or {@code null}), and whether a vectorization outbox row was enqueued.
   */
  record StoreOutcome(Memory stored, String supersededId, boolean enqueuedVector) {
  }

  /**
   * List active (non-superseded) memories, optionally filtered by type and/or session.
   * Null filters mean "no filter on that dimension".
   */
  List<Memory> listMemories(String profileId, MemoryType typeFilter, String sessionFilter);

  /**
   * Mark a memory superseded (logical delete, SPEC 5.6); never physically deletes.
   * Returns {@code true} if a matching active memory was found and updated.
   */
  boolean forgetMemory(String profileId, String memoryId);

  /**
   * Export all memories for a profile as NDJSON-friendly rows (SPEC 13).
   */
  List<ExportRow> exportProfile(String profileId);

  /**
   * Phase 1 retrieval: keyed + lexical (LIKE) lookup over active memories and message text.
   * Shaped so Phase 3 can add FTS/vector/HyDE channels + RRF without changing callers.
   */
  List<RecallCandidate> findRecallCandidates(String profileId, String query, int limit);
}
