package dev.alvo.pieria.storage;

import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
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
