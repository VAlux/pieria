package dev.alvo.pieria.storage;

import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.OutboxEntry;
import dev.alvo.pieria.domain.Profile;
import dev.alvo.pieria.domain.RecallCandidate;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Embedded SQLite backend for {@link MemoryStore} (Phase 1). Hand-written SQL against the V1 schema
 * via Spring's {@link JdbcClient}. Writes are content-addressed and idempotent via
 * {@code INSERT OR IGNORE}; deletes are logical (supersession).
 */
@Repository
public class SqliteMemoryStore implements MemoryStore {

  private static final Logger log = LoggerFactory.getLogger(SqliteMemoryStore.class);

  private final JdbcClient jdbc;

  public SqliteMemoryStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Serialize a float vector as little-endian float32 bytes (4 bytes per dimension) for the
   * {@code embedding BLOB} column.
   */
  private static byte[] encodeEmbedding(float[] embedding) {
    ByteBuffer buffer = ByteBuffer.allocate(embedding.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (float value : embedding) {
      buffer.putFloat(value);
    }
    return buffer.array();
  }

  private static Profile mapProfile(ResultSet rs) throws SQLException {
    return new Profile(
      rs.getString("id"),
      rs.getString("name"),
      Instant.parse(rs.getString("created_at")));
  }

  private static Memory mapMemory(ResultSet rs) throws SQLException {
    return new Memory(
      rs.getString("id"),
      rs.getString("session_id"),
      MemoryType.fromWire(rs.getString("type")),
      rs.getString("content"),
      rs.getString("topic_key"),
      rs.getString("supersedes"),
      rs.getInt("superseded") != 0,
      rs.getString("payload"),
      rs.getString("embed_text"),
      Instant.parse(rs.getString("created_at")));
  }

  @Override
  @Transactional
  public Profile getOrCreateProfile(String name) {
    return findProfile(name).orElseGet(() -> {
      Profile created = new Profile(UUID.randomUUID().toString(), name, Instant.now());

      try {
        jdbc.sql("INSERT INTO profiles (id, name, created_at) VALUES (?, ?, ?)")
          .params(created.id(), created.name(), created.createdAt().toString())
          .update();

        return created;
      } catch (DuplicateKeyException raceCondition) {
        // Another writer created it between our SELECT and INSERT: re-select the winner.
        return findProfile(name).orElseThrow(() -> raceCondition);
      }
    });
  }

  @Override
  public Optional<Profile> findProfile(String name) {
    return jdbc.sql("SELECT id, name, created_at FROM profiles WHERE name = ?")
      .param(name)
      .query((rs, rowNum) -> mapProfile(rs))
      .optional();
  }

  @Override
  @Transactional
  public void insertMessages(String profileId, String sessionId, List<Message> messages) {
    if (messages == null || messages.isEmpty()) {
      return;
    }

    for (Message message : messages) {
      String id = ContentId.forMessage(sessionId, message.role(), message.content());
      String createdAt = (message.createdAt() == null ? Instant.now() : message.createdAt()).toString();
      jdbc.sql("""
          INSERT OR IGNORE INTO messages \
          (id, profile_id, session_id, role, content, created_at) \
          VALUES (?, ?, ?, ?, ?, ?)""")
        .params(id, profileId, sessionId, message.role(), message.content(), createdAt)
        .update();
    }
  }

  @Override
  @Transactional
  public Memory insertMemory(String profileId, Memory memory) {
    String id = memory.id() != null
      ? memory.id()
      : ContentId.forMemory(memory.sessionId(), memory.type(), memory.content());
    Instant createdAt = memory.createdAt() == null ? Instant.now() : memory.createdAt();
    String payload = memory.payload() == null ? "{}" : memory.payload();

    jdbc.sql("""
        INSERT OR IGNORE INTO memories \
        (id, profile_id, session_id, type, content, topic_key, supersedes, \
        superseded, payload, embed_text, created_at) \
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
      .params(
        id,
        profileId,
        memory.sessionId(),
        memory.type().wire(),
        memory.content(),
        memory.topicKey(),
        memory.supersedes(),
        memory.superseded() ? 1 : 0,
        payload,
        memory.embedText(),
        createdAt.toString())
      .update();

    return new Memory(
      id,
      memory.sessionId(),
      memory.type(),
      memory.content(),
      memory.topicKey(),
      memory.supersedes(),
      memory.superseded(),
      payload,
      memory.embedText(),
      createdAt);
  }

  @Override
  @Transactional
  public StoreOutcome store(String profileId, Memory memory) {
    String supersededId = null;

    String id = memory.id() != null
      ? memory.id()
      : ContentId.forMemory(memory.sessionId(), memory.type(), memory.content());

    boolean keyed = (memory.type() == MemoryType.FACT || memory.type() == MemoryType.INSTRUCTION)
      && memory.topicKey() != null;
    if (keyed) {
      // EVENT and TASK are append-only; only FACT/INSTRUCTION supersede on a shared topic key.
      Optional<String> activeId = jdbc.sql(
          """
            SELECT id FROM memories \
            WHERE profile_id = ? AND type = ? AND topic_key = ? AND superseded = 0 \
            ORDER BY created_at DESC LIMIT 1""")
        .params(profileId, memory.type().wire(), memory.topicKey())
        .query(String.class)
        .optional();
      // Skip when the active row IS the incoming memory (identical content-addressed id): a
      // re-ingest must stay idempotent, not supersede the row it would re-insert.
      if (activeId.isPresent() && !activeId.get().equals(id)) {
        supersededId = activeId.get();
        jdbc.sql("UPDATE memories SET superseded = 1, embedding = NULL WHERE id = ?")
          .param(supersededId)
          .update();
        // Drop any pending vectorization work for the now-superseded row.
        jdbc.sql("DELETE FROM vectorization_outbox WHERE memory_id = ?")
          .param(supersededId)
          .update();
      }
    }

    Memory toInsert = new Memory(
      id,
      memory.sessionId(),
      memory.type(),
      memory.content(),
      memory.topicKey(),
      supersededId != null ? supersededId : memory.supersedes(),
      memory.superseded(),
      memory.payload(),
      memory.embedText(),
      memory.createdAt());

    Memory stored = insertMemory(profileId, toInsert);

    boolean enqueuedVector = false;
    if (memory.type() != MemoryType.TASK) {
      // Tasks are not embedded; everything else gets an idempotent outbox entry.
      int affected = jdbc.sql(
          """
            INSERT OR IGNORE INTO vectorization_outbox (memory_id, enqueued_at, attempts) \
            VALUES (?, ?, 0)""")
        .params(stored.id(), Instant.now().toString())
        .update();
      enqueuedVector = affected > 0;
    }

    return new StoreOutcome(stored, supersededId, enqueuedVector);
  }

  @Override
  public List<Memory> findActiveByTopicKey(String profileId, MemoryType type, String topicKey) {
    if (topicKey == null) {
      return List.of();
    }
    return jdbc.sql(
        """
          SELECT id, session_id, type, content, topic_key, supersedes, superseded, \
          payload, embed_text, created_at FROM memories \
          WHERE profile_id = ? AND type = ? AND topic_key = ? AND superseded = 0 \
          ORDER BY created_at DESC""")
      .params(profileId, type.wire(), topicKey)
      .query((rs, rowNum) -> mapMemory(rs))
      .list();
  }

  @Override
  public Optional<Memory> findMemoryById(String memoryId) {
    return jdbc.sql(
        """
          SELECT id, session_id, type, content, topic_key, supersedes, superseded, \
          payload, embed_text, created_at FROM memories WHERE id = ?""")
      .param(memoryId)
      .query((rs, rowNum) -> mapMemory(rs))
      .optional();
  }

  @Override
  public List<OutboxEntry> drainOutbox(int batchSize) {
    if (batchSize <= 0) {
      return List.of();
    }
    return jdbc.sql("SELECT memory_id, attempts FROM vectorization_outbox ORDER BY enqueued_at LIMIT ?")
      .param(batchSize)
      .query((rs, rowNum) -> new OutboxEntry(rs.getString("memory_id"), rs.getInt("attempts")))
      .list();
  }

  @Override
  @Transactional
  public void recordOutboxFailure(String memoryId, String lastError) {
    jdbc.sql("UPDATE vectorization_outbox SET attempts = attempts + 1 WHERE memory_id = ?")
      .param(memoryId)
      .update();
    log.warn("Vectorization attempt failed for memory {}: {}", memoryId, lastError);
  }

  @Override
  @Transactional
  public void deleteOutboxRow(String memoryId) {
    jdbc.sql("DELETE FROM vectorization_outbox WHERE memory_id = ?")
      .param(memoryId)
      .update();
  }

  @Override
  @Transactional
  public void completeVectorization(String memoryId, float[] embedding) {
    // Write the embedding first, then remove the outbox row, in the same transaction.
    jdbc.sql("UPDATE memories SET embedding = ? WHERE id = ?")
      .params(encodeEmbedding(embedding), memoryId)
      .update();

    jdbc.sql("DELETE FROM vectorization_outbox WHERE memory_id = ?")
      .param(memoryId)
      .update();
  }

  @Override
  public List<Memory> listMemories(String profileId, MemoryType typeFilter, String sessionFilter) {
    StringBuilder sql = new StringBuilder(
      """
        SELECT id, session_id, type, content, topic_key, supersedes, superseded, \
        payload, embed_text, created_at FROM memories \
        WHERE profile_id = ? AND superseded = 0""");

    List<Object> params = new ArrayList<>();
    params.add(profileId);
    if (typeFilter != null) {
      sql.append(" AND type = ?");
      params.add(typeFilter.wire());
    }
    if (sessionFilter != null) {
      sql.append(" AND session_id = ?");
      params.add(sessionFilter);
    }
    sql.append(" ORDER BY created_at DESC");

    return jdbc.sql(sql.toString())
      .params(params)
      .query((rs, rowNum) -> mapMemory(rs))
      .list();
  }

  @Override
  @Transactional
  public boolean forgetMemory(String profileId, String memoryId) {
    int affected = jdbc.sql("UPDATE memories SET superseded = 1 WHERE id = ? AND profile_id = ? AND superseded = 0")
      .params(memoryId, profileId)
      .update();

    return affected > 0;
  }

  @Override
  public List<ExportRow> exportProfile(String profileId) {
    String profileName = jdbc.sql("SELECT name FROM profiles WHERE id = ?")
      .param(profileId)
      .query(String.class)
      .optional()
      .orElse(null);
    if (profileName == null) {
      return List.of();
    }
    List<Memory> memories = jdbc.sql(
        """
          SELECT id, session_id, type, content, topic_key, supersedes, superseded, \
          payload, embed_text, created_at FROM memories \
          WHERE profile_id = ? ORDER BY created_at DESC""")
      .param(profileId)
      .query((rs, rowNum) -> mapMemory(rs))
      .list();

    List<ExportRow> rows = new ArrayList<>(memories.size());
    for (Memory memory : memories) {
      rows.add(new ExportRow(profileName, memory));
    }

    return rows;
  }

  @Override
  public List<RecallCandidate> findRecallCandidates(String profileId, String query, int limit) {
    if (query == null || query.isBlank() || limit <= 0) {
      return List.of();
    }

    List<String> terms = new ArrayList<>();
    for (String token : query.toLowerCase(java.util.Locale.ROOT).split("\\s+")) {
      String term = token.trim();
      if (!term.isEmpty() && !terms.contains(term)) {
        terms.add(term);
      }
    }
    if (terms.isEmpty()) {
      return List.of();
    }

    List<Memory> active = listMemories(profileId, null, null);
    List<String> matchedSessions = getMatchedSessions(profileId, terms);
    List<Scored> ordered = getScoredList(active, terms, matchedSessions);

    List<RecallCandidate> recallCandidates = new ArrayList<>();
    for (Scored scored : ordered) {
      if (recallCandidates.size() >= limit) {
        break;
      }

      recallCandidates.add(new RecallCandidate(scored.memory, scored.score, scored.source));
    }

    return recallCandidates;
  }

  private static List<Scored> getScoredList(List<Memory> active, List<String> terms, List<String> matchedSessions) {
    Map<String, Scored> scored = new LinkedHashMap<>();

    for (Memory memory : active) {
      String content = memory.content() == null ? "" : memory.content().toLowerCase(java.util.Locale.ROOT);
      int contentMatches = 0;
      for (String term : terms) {
        if (content.contains(term)) {
          contentMatches++;
        }
      }
      if (contentMatches > 0) {
        scored.put(memory.id(), new Scored(memory, contentMatches, "fts-memory"));
      } else if (memory.sessionId() != null && matchedSessions.contains(memory.sessionId())) {
        // Surfaced indirectly via a matching raw message in the same session.
        scored.put(memory.id(), new Scored(memory, 1, "key"));
      }
    }

    ArrayList<Scored> scoredList = new ArrayList<>(scored.values());

    scoredList.sort(Comparator
      .comparingInt((Scored s) -> s.score).reversed()
      .thenComparing(s -> s.memory.createdAt(), Comparator.reverseOrder()));

    return scoredList;
  }

  private @NonNull List<String> getMatchedSessions(String profileId, List<String> terms) {
    // Session ids that have a message matching any term: lets message hits surface their memories.
    return terms.stream().flatMap(term -> jdbc
        .sql("SELECT DISTINCT session_id FROM messages WHERE profile_id = ? AND lower(content) LIKE ?")
        .params(profileId, "%" + term + "%")
        .query(String.class)
        .list().stream())
      .filter(Objects::nonNull)
      .distinct()
      .toList();
  }

  private record Scored(Memory memory, int score, String source) {
  }
}
