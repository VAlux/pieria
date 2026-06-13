package dev.alvo.pieria.storage;

import dev.alvo.pieria.config.DataSourceConfig.VecCapability;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.ContentId;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Embedded SQLite backend for {@link MemoryStore}. Hand-written SQL against the V1 schema
 * via Spring's {@link JdbcClient}. Writes are content-addressed and idempotent via
 * {@code INSERT OR IGNORE}; deletes are logical (supersession).
 */
@Repository
public class SqliteMemoryStore implements MemoryStore {

  private static final Logger log = LoggerFactory.getLogger(SqliteMemoryStore.class);

  private final JdbcClient jdbc;
  private final VecCapability vecCapability;
  private final boolean vectorEnabled;

  /**
   * Production constructor: wires the sqlite-vec capability flag and the retrieval feature switch.
   */
  @org.springframework.beans.factory.annotation.Autowired
  public SqliteMemoryStore(JdbcClient jdbc, VecCapability vecCapability, PieriaProperties properties) {
    this.jdbc = jdbc;
    this.vecCapability = vecCapability;
    this.vectorEnabled = properties.retrieval().vectorEnabled();
  }

  /**
   * Constructor for tests: no sqlite-vec capability (vector search reports unavailable).
   */
  public SqliteMemoryStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
    this.vecCapability = null;
    this.vectorEnabled = false;
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

  /**
   * Inverse of {@link #encodeEmbedding}: decode little-endian float32 bytes back to a float vector.
   */
  private static float[] decodeEmbedding(byte[] blob) {
    ByteBuffer buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
    float[] out = new float[blob.length / Float.BYTES];
    for (int i = 0; i < out.length; i++) {
      out[i] = buffer.getFloat();
    }
    return out;
  }

  /**
   * Render a float vector as a sqlite-vec JSON array literal (the most portable input form for
   * {@code vec0} inserts and KNN queries).
   */
  private static String toVecJson(float[] embedding) {
    StringBuilder sb = new StringBuilder(embedding.length * 8).append('[');
    for (int i = 0; i < embedding.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(embedding[i]);
    }
    return sb.append(']').toString();
  }

  /**
   * Build a safe FTS5 MATCH expression from arbitrary user text: lowercase, split on non-word
   * characters, and OR the surviving tokens as double-quoted strings so punctuation/operators in
   * the raw query cannot raise an FTS5 syntax error. Returns {@code null} when no usable token.
   */
  private static String toFtsMatch(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    List<String> tokens = new ArrayList<>();
    for (String token : raw.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
      if (!token.isBlank() && !tokens.contains(token)) {
        // Double-quote and escape any embedded quotes; an FTS5 string token matches literally.
        tokens.add('"' + token.replace("\"", "\"\"") + '"');
      }
    }
    return tokens.isEmpty() ? null : String.join(" OR ", tokens);
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
      .query((rs, _) -> mapProfile(rs))
      .optional();
  }

  @Override
  public List<ProfileCount> listProfiles() {
    return jdbc.sql("""
        SELECT p.id, p.name, p.created_at, \
        COUNT(m.id) FILTER (WHERE m.superseded = 0) AS active_count \
        FROM profiles p LEFT JOIN memories m ON m.profile_id = p.id \
        GROUP BY p.id, p.name, p.created_at \
        ORDER BY p.name""")
      .query((rs, _) -> new ProfileCount(mapProfile(rs), rs.getLong("active_count")))
      .list();
  }

  @Override
  public ProfileStats profileStats(String profileId) {
    // Per-type active counts in one grouped scan; missing types default to 0 below.
    Map<String, Long> byType = new LinkedHashMap<>();
    for (MemoryType type : MemoryType.values()) {
      byType.put(type.wire(), 0L);
    }
    jdbc.sql("SELECT type, COUNT(*) AS n FROM memories WHERE profile_id = ? AND superseded = 0 GROUP BY type")
      .param(profileId)
      .query((rs, _) -> Map.entry(rs.getString("type"), rs.getLong("n")))
      .list()
      .forEach(e -> byType.put(e.getKey(), e.getValue()));

    long totalActive = byType.values().stream().mapToLong(Long::longValue).sum();

    long superseded = jdbc.sql("SELECT COUNT(*) FROM memories WHERE profile_id = ? AND superseded = 1")
      .param(profileId)
      .query(Long.class)
      .single();

    long sessions = jdbc.sql(
        "SELECT COUNT(DISTINCT session_id) FROM memories WHERE profile_id = ? AND superseded = 0 AND session_id IS NOT NULL")
      .param(profileId)
      .query(Long.class)
      .single();

    // MIN/MAX over ISO-8601 strings sorts chronologically; null when the profile has no active rows.
    String first = jdbc.sql("SELECT MIN(created_at) FROM memories WHERE profile_id = ? AND superseded = 0")
      .param(profileId)
      .query(String.class)
      .optional()
      .orElse(null);
    String last = jdbc.sql("SELECT MAX(created_at) FROM memories WHERE profile_id = ? AND superseded = 0")
      .param(profileId)
      .query(String.class)
      .optional()
      .orElse(null);

    return new ProfileStats(totalActive, byType, superseded, sessions,
      first == null ? null : Instant.parse(first),
      last == null ? null : Instant.parse(last));
  }

  @Override
  public void putProfileConfig(String profileId, String configJson) {
    jdbc.sql("""
        INSERT INTO profile_config (profile_id, config_json, updated_at) VALUES (?, ?, ?) \
        ON CONFLICT (profile_id) DO UPDATE SET config_json = excluded.config_json, \
        updated_at = excluded.updated_at""")
      .params(profileId, configJson, Instant.now().toString())
      .update();
  }

  @Override
  public Optional<String> getProfileConfig(String profileId) {
    return jdbc.sql("SELECT config_json FROM profile_config WHERE profile_id = ?")
      .param(profileId)
      .query(String.class)
      .optional();
  }

  @Override
  public void clearProfileConfig(String profileId) {
    jdbc.sql("DELETE FROM profile_config WHERE profile_id = ?")
      .param(profileId)
      .update();
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
  public StoreOutcome store(String profileId, Memory memory, GraphFragment graph) {
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
        // Remove the superseded row's vector in the same transaction so it never
        // surfaces in vector results.
        deleteEmbedding(supersededId);
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

    // Persist the extracted graph in the same transaction, tagging each edge with this memory's id
    // as provenance. The fragment is empty for the two-arg store path and for TASK memories.
    persistGraph(profileId, stored.id(), graph);

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
          SELECT id, session_id, type, content, topic_key, supersedes, superseded, payload, embed_text, created_at \
          FROM memories \
          WHERE profile_id = ? AND type = ? AND topic_key = ? AND superseded = 0 \
          ORDER BY created_at DESC""")
      .params(profileId, type.wire(), topicKey)
      .query((rs, _) -> mapMemory(rs))
      .list();
  }

  @Override
  public Optional<Memory> findMemoryById(String memoryId) {
    return jdbc.sql(
        """
          SELECT id, session_id, type, content, topic_key, supersedes, superseded, payload, embed_text, created_at \
          FROM memories \
          WHERE id = ?""")
      .param(memoryId)
      .query((rs, _) -> mapMemory(rs))
      .optional();
  }

  @Override
  public List<OutboxEntry> drainOutbox(int batchSize) {
    if (batchSize <= 0) {
      return List.of();
    }
    return jdbc.sql("SELECT memory_id, attempts FROM vectorization_outbox ORDER BY enqueued_at LIMIT ?")
      .param(batchSize)
      .query((rs, _) -> new OutboxEntry(rs.getString("memory_id"), rs.getInt("attempts")))
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
    // Write the BLOB first, mirror it into the sqlite-vec index, then remove the outbox row, all in
    // one transaction. encodeEmbedding throws on a null vector before any write happens.
    byte[] encoded = encodeEmbedding(embedding);
    jdbc.sql("UPDATE memories SET embedding = ? WHERE id = ?")
      .params(encoded, memoryId)
      .update();

    upsertEmbedding(memoryId, embedding);

    jdbc.sql("DELETE FROM vectorization_outbox WHERE memory_id = ?")
      .param(memoryId)
      .update();
  }

  @Override
  public OptionalLong vectorizationOutboxDepth() {
    Long count = jdbc.sql("SELECT COUNT(*) FROM vectorization_outbox")
      .query(Long.class)
      .single();
    return OptionalLong.of(count == null ? 0L : count);
  }

  @Override
  public List<Memory> listMemories(String profileId, MemoryType typeFilter, String sessionFilter) {
    StringBuilder sql = new StringBuilder(
      """
        SELECT id, session_id, type, content, topic_key, supersedes, superseded, payload, embed_text, created_at \
        FROM memories \
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
      .query((rs, _) -> mapMemory(rs))
      .list();
  }

  @Override
  @Transactional
  public boolean forgetMemory(String profileId, String memoryId) {
    int affected = jdbc.sql("UPDATE memories SET superseded = 1, embedding = NULL WHERE id = ? AND profile_id = ? AND superseded = 0")
      .params(memoryId, profileId)
      .update();

    if (affected > 0) {
      // A forgotten memory must drop out of vector results too.
      deleteEmbedding(memoryId);
    }

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
      .query((rs, _) -> mapMemory(rs))
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

  // ---- sqlite-vec index + FTS5 retrieval channels ----

  private static final String MEMORY_COLUMNS =
    "id, session_id, type, content, topic_key, supersedes, superseded, payload, embed_text, created_at";

  @Override
  public boolean isVectorSearchAvailable() {
    return vectorEnabled && vecCapability != null && vecCapability.isLoaded();
  }

  @Override
  @Transactional
  public void upsertEmbedding(String memoryId, float[] embedding) {
    if (!isVectorSearchAvailable() || embedding == null) {
      return;
    }
    // vec0 has no UPSERT; delete-then-insert keeps memory_id unique and idempotent.
    jdbc.sql("DELETE FROM memories_vec WHERE memory_id = ?").param(memoryId).update();
    jdbc.sql("INSERT INTO memories_vec (memory_id, embedding) VALUES (?, ?)")
      .params(memoryId, toVecJson(embedding))
      .update();
  }

  @Override
  @Transactional
  public void deleteEmbedding(String memoryId) {
    if (!isVectorSearchAvailable()) {
      return;
    }
    jdbc.sql("DELETE FROM memories_vec WHERE memory_id = ?").param(memoryId).update();
  }

  @Override
  public List<Memory> searchMemoriesFts(String profileId, String matchQuery, int limit) {
    String match = toFtsMatch(matchQuery);
    if (match == null || limit <= 0) {
      return List.of();
    }
    // Join FTS rowid back to memories; filter to this profile's active set, rank best-first.
    return jdbc.sql(
        """
          SELECT m.id, m.session_id, m.type, m.content, m.topic_key, m.supersedes, \
          m.superseded, m.payload, m.embed_text, m.created_at \
          FROM memories_fts f \
          JOIN memories m ON m.rowid = f.rowid \
          WHERE memories_fts MATCH ? AND m.profile_id = ? AND m.superseded = 0 \
          ORDER BY f.rank LIMIT ?""")
      .params(match, profileId, limit)
      .query((rs, _) -> mapMemory(rs))
      .list();
  }

  @Override
  public List<Memory> searchMemoriesByMessageFts(String profileId, String matchQuery, int limit) {
    String match = toFtsMatch(matchQuery);
    if (match == null || limit <= 0) {
      return List.of();
    }
    // Active memories whose session has a matching raw message; rank by best (lowest) message rank
    // for the session, then recency. The safety net for verbatim details the extractor generalized.
    return jdbc.sql(
        """
          SELECT m.id, m.session_id, m.type, m.content, m.topic_key, m.supersedes, \
          m.superseded, m.payload, m.embed_text, m.created_at \
          FROM memories m \
          JOIN ( \
            SELECT msg.session_id AS sid, MIN(f.rank) AS best_rank \
            FROM messages_fts f \
            JOIN messages msg ON msg.rowid = f.rowid \
            WHERE messages_fts MATCH ? AND msg.profile_id = ? \
            GROUP BY msg.session_id \
          ) hit ON hit.sid = m.session_id \
          WHERE m.profile_id = ? AND m.superseded = 0 \
          ORDER BY hit.best_rank, m.created_at DESC LIMIT ?""")
      .params(match, profileId, profileId, limit)
      .query((rs, _) -> mapMemory(rs))
      .list();
  }

  @Override
  public List<Memory> exactKeyLookup(String profileId, List<String> topicKeys, int limit) {
    if (topicKeys == null || topicKeys.isEmpty() || limit <= 0) {
      return List.of();
    }
    // De-duplicate while preserving caller priority order.
    List<String> keys = new ArrayList<>();
    for (String key : topicKeys) {
      if (key != null && !key.isBlank() && !keys.contains(key)) {
        keys.add(key);
      }
    }
    if (keys.isEmpty()) {
      return List.of();
    }

    String placeholders = String.join(", ", keys.stream().map(_ -> "?").toList());
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.add(MemoryType.FACT.wire());
    params.add(MemoryType.INSTRUCTION.wire());
    params.addAll(keys);
    params.add(limit);

    List<Memory> matches = jdbc.sql(
        "SELECT " + MEMORY_COLUMNS + " FROM memories "
          + "WHERE profile_id = ? AND type IN (?, ?) AND superseded = 0 "
          + "AND topic_key IN (" + placeholders + ") "
          + "ORDER BY created_at DESC LIMIT ?")
      .params(params)
      .query((rs, _) -> mapMemory(rs))
      .list();

    // Re-order by the priority of the key in the input list, then created_at desc (SQL gave us the
    // latter within each key already, but the final comparator makes priority the primary sort).
    matches.sort(Comparator
      .comparingInt((Memory m) -> {
        int idx = keys.indexOf(m.topicKey());
        return idx < 0 ? Integer.MAX_VALUE : idx;
      })
      .thenComparing(Memory::createdAt, Comparator.reverseOrder()));

    return matches.size() > limit ? new ArrayList<>(matches.subList(0, limit)) : matches;
  }

  @Override
  public List<Memory> vectorSearch(String profileId, float[] queryEmbedding, int limit) {
    if (!isVectorSearchAvailable() || queryEmbedding == null || limit <= 0) {
      return List.of();
    }
    // sqlite-vec KNN: the `embedding MATCH ? AND k = ?` form returns the k nearest rows ordered by
    // distance. Join to memories and re-filter to the active, non-task, in-profile set defensively.
    return jdbc.sql(
        """
          SELECT \
           m.id,\
           m.session_id,\
           m.type,\
           m.content,\
           m.topic_key,\
           m.supersedes, \
           m.superseded, \
           m.payload, \
           m.embed_text, \
           m.created_at \
          FROM memories_vec v \
          JOIN memories m ON m.id = v.memory_id \
          WHERE v.embedding MATCH ? AND k = ? \
          AND m.profile_id = ? AND m.superseded = 0 AND m.type != ? \
          ORDER BY v.distance""")
      .params(toVecJson(queryEmbedding), limit, profileId, MemoryType.TASK.wire())
      .query((rs, _) -> mapMemory(rs))
      .list();
  }

  @Override
  @Transactional
  public int backfillVectors() {
    if (!isVectorSearchAvailable()) {
      return 0;
    }
    // Active, vector-eligible memories with a stored BLOB but no vec row yet.
    record Pending(String id, byte[] embedding) {
    }
    List<Pending> rows = jdbc.sql(
        """
          SELECT m.id AS id, m.embedding AS embedding FROM memories m \
          WHERE m.superseded = 0 AND m.type != ? AND m.embedding IS NOT NULL \
          AND NOT EXISTS (SELECT 1 FROM memories_vec v WHERE v.memory_id = m.id)""")
      .param(MemoryType.TASK.wire())
      .query((rs, _) -> new Pending(rs.getString("id"), rs.getBytes("embedding")))
      .list();

    int count = 0;
    for (Pending row : rows) {
      if (row.embedding() == null || row.embedding().length == 0) {
        continue;
      }
      upsertEmbedding(row.id(), decodeEmbedding(row.embedding()));
      count++;
    }
    return count;
  }

  // ---- entity-relation graph ----

  private static final String ENTITY_COLUMNS = "id, profile_id, type, name, payload, created_at";

  private static Entity mapEntity(ResultSet rs) throws SQLException {
    return new Entity(
      rs.getString("id"),
      rs.getString("profile_id"),
      rs.getString("type"),
      rs.getString("name"),
      rs.getString("payload"),
      Instant.parse(rs.getString("created_at")));
  }

  /**
   * Persist a fragment's entities and edges within the caller's ({@link #store}) transaction. Nodes
   * are upserted first so every edge endpoint exists; edges are tagged with {@code memoryId}.
   */
  private void persistGraph(String profileId, String memoryId, GraphFragment graph) {
    if (graph == null || graph.isEmpty()) {
      return;
    }
    for (Entity e : graph.allEntities()) {
      if (e.name() == null || e.name().isBlank() || e.type() == null || e.type().isBlank()) {
        continue;
      }
      upsertEntity(profileId, Entity.of(e.type(), e.name(), e.payload()));
    }
    for (GraphFragment.EdgeTriple t : graph.triples()) {
      if (t.relation() == null || t.relation().isBlank()
        || t.sourceName() == null || t.sourceName().isBlank()
        || t.targetName() == null || t.targetName().isBlank()) {
        continue;
      }
      String sourceId = ContentId.forEntity(profileId, t.sourceType(), t.sourceName());
      String targetId = ContentId.forEntity(profileId, t.targetType(), t.targetName());
      upsertEdge(profileId, new Edge(null, profileId, sourceId, targetId, t.relation(), memoryId, null));
    }
  }

  @Override
  @Transactional
  public Entity upsertEntity(String profileId, Entity entity) {
    String id = entity.id() != null
      ? entity.id()
      : ContentId.forEntity(profileId, entity.type(), entity.name());
    Instant createdAt = entity.createdAt() == null ? Instant.now() : entity.createdAt();
    String payload = entity.payload() == null ? "{}" : entity.payload();

    jdbc.sql("""
        INSERT OR IGNORE INTO entities (id, profile_id, type, name, payload, created_at) \
        VALUES (?, ?, ?, ?, ?, ?)""")
      .params(id, profileId, entity.type(), entity.name(), payload, createdAt.toString())
      .update();

    return new Entity(id, profileId, entity.type(), entity.name(), payload, createdAt);
  }

  @Override
  @Transactional
  public Edge upsertEdge(String profileId, Edge edge) {
    String id = edge.id() != null
      ? edge.id()
      : ContentId.forEdge(profileId, edge.sourceEntityId(), edge.relation(), edge.targetEntityId(), edge.memoryId());
    Instant createdAt = edge.createdAt() == null ? Instant.now() : edge.createdAt();

    jdbc.sql("""
        INSERT OR IGNORE INTO edges \
        (id, profile_id, source_entity_id, target_entity_id, relation, memory_id, created_at) \
        VALUES (?, ?, ?, ?, ?, ?, ?)""")
      .params(id, profileId, edge.sourceEntityId(), edge.targetEntityId(), edge.relation(),
        edge.memoryId(), createdAt.toString())
      .update();

    return new Edge(id, profileId, edge.sourceEntityId(), edge.targetEntityId(), edge.relation(),
      edge.memoryId(), createdAt);
  }

  @Override
  public List<Entity> findEntitiesByName(String profileId, List<String> names, int limit) {
    if (names == null || names.isEmpty() || limit <= 0) {
      return List.of();
    }
    List<String> distinct = names.stream()
      .filter(n -> n != null && !n.isBlank())
      .distinct()
      .toList();
    if (distinct.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(", ", distinct.stream().map(_ -> "?").toList());
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.addAll(distinct);
    params.add(limit);
    return jdbc.sql("SELECT " + ENTITY_COLUMNS + " FROM entities "
        + "WHERE profile_id = ? AND name IN (" + placeholders + ") ORDER BY name LIMIT ?")
      .params(params)
      .query((rs, _) -> mapEntity(rs))
      .list();
  }

  @Override
  public List<Entity> entitiesForMemories(String profileId, List<String> memoryIds, int limit) {
    if (memoryIds == null || memoryIds.isEmpty() || limit <= 0) {
      return List.of();
    }
    List<String> distinct = memoryIds.stream().filter(Objects::nonNull).distinct().toList();
    if (distinct.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(", ", distinct.stream().map(_ -> "?").toList());
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.addAll(distinct);
    params.add(limit);
    // Entities on either end of an edge whose source memory is active and is one of memoryIds.
    return jdbc.sql("SELECT DISTINCT en.id, en.profile_id, en.type, en.name, en.payload, en.created_at "
        + "FROM entities en "
        + "JOIN edges e ON (e.source_entity_id = en.id OR e.target_entity_id = en.id) "
        + "JOIN memories m ON m.id = e.memory_id "
        + "WHERE en.profile_id = ? AND m.superseded = 0 "
        + "AND e.memory_id IN (" + placeholders + ") "
        + "ORDER BY en.name LIMIT ?")
      .params(params)
      .query((rs, _) -> mapEntity(rs))
      .list();
  }

  @Override
  public List<String> neighborhood(String profileId, List<String> seedEntityIds, int depth, int fanout) {
    if (seedEntityIds == null || seedEntityIds.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> visited = new LinkedHashSet<>();
    List<String> frontier = new ArrayList<>();
    for (String s : seedEntityIds) {
      if (s != null && !s.isBlank() && visited.add(s)) {
        frontier.add(s);
      }
    }
    int hops = Math.max(0, depth);
    for (int d = 0; d < hops && !frontier.isEmpty(); d++) {
      List<String> next = activeNeighbors(profileId, frontier, fanout);
      List<String> newFrontier = new ArrayList<>();
      for (String n : next) {
        if (visited.add(n)) {
          newFrontier.add(n);
        }
      }
      frontier = newFrontier;
    }
    return List.copyOf(visited);
  }

  /**
   * One hop out from {@code frontier} over active edges (both directions), most-recent first,
   * bounded by {@code fanout}. Returns distinct neighbor entity ids in that order.
   */
  private List<String> activeNeighbors(String profileId, List<String> frontier, int fanout) {
    if (frontier.isEmpty() || fanout <= 0) {
      return List.of();
    }
    String placeholders = String.join(", ", frontier.stream().map(_ -> "?").toList());
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.addAll(frontier);
    params.add(profileId);
    params.addAll(frontier);
    params.add(fanout);
    List<String> rows = jdbc.sql("SELECT neighbor FROM ( "
        + "  SELECT e.target_entity_id AS neighbor, e.created_at AS ca FROM edges e "
        + "    JOIN memories m ON m.id = e.memory_id "
        + "    WHERE e.profile_id = ? AND m.superseded = 0 AND e.source_entity_id IN (" + placeholders + ") "
        + "  UNION ALL "
        + "  SELECT e.source_entity_id AS neighbor, e.created_at AS ca FROM edges e "
        + "    JOIN memories m ON m.id = e.memory_id "
        + "    WHERE e.profile_id = ? AND m.superseded = 0 AND e.target_entity_id IN (" + placeholders + ") "
        + ") ORDER BY ca DESC, neighbor ASC LIMIT ?")
      .params(params)
      .query(String.class)
      .list();
    LinkedHashSet<String> distinct = new LinkedHashSet<>(rows);
    return List.copyOf(distinct);
  }

  @Override
  public List<Memory> findMemoriesByEntities(String profileId, List<String> entityIds, int limit) {
    if (entityIds == null || entityIds.isEmpty() || limit <= 0) {
      return List.of();
    }
    List<String> distinct = entityIds.stream()
      .filter(e -> e != null && !e.isBlank())
      .distinct()
      .toList();
    if (distinct.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(", ", distinct.stream().map(_ -> "?").toList());
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.addAll(distinct);
    params.add(profileId);
    params.addAll(distinct);
    // Active memories touched by an edge incident to any of the entities, with the touching entity
    // id so we can rank by proximity (earliest-listed entity) then recency in Java.
    record Row(Memory memory, String entityId) {
    }
    List<Row> rows = jdbc.sql("SELECT m.id, m.session_id, m.type, m.content, m.topic_key, "
        + "m.supersedes, m.superseded, m.payload, m.embed_text, m.created_at, x.eid AS eid FROM ( "
        + "  SELECT e.memory_id AS mid, e.source_entity_id AS eid FROM edges e "
        + "    WHERE e.profile_id = ? AND e.source_entity_id IN (" + placeholders + ") "
        + "  UNION "
        + "  SELECT e.memory_id AS mid, e.target_entity_id AS eid FROM edges e "
        + "    WHERE e.profile_id = ? AND e.target_entity_id IN (" + placeholders + ") "
        + ") x JOIN memories m ON m.id = x.mid WHERE m.superseded = 0")
      .params(params)
      .query((rs, _) -> new Row(mapMemory(rs), rs.getString("eid")))
      .list();

    Map<String, Memory> byId = new LinkedHashMap<>();
    Map<String, Integer> bestIndex = new HashMap<>();
    for (Row row : rows) {
      int idx = distinct.indexOf(row.entityId());
      int proximity = idx < 0 ? Integer.MAX_VALUE : idx;
      byId.putIfAbsent(row.memory().id(), row.memory());
      bestIndex.merge(row.memory().id(), proximity, Math::min);
    }

    List<Memory> ordered = new ArrayList<>(byId.values());
    ordered.sort(Comparator
      .comparingInt((Memory m) -> bestIndex.getOrDefault(m.id(), Integer.MAX_VALUE))
      .thenComparing(Memory::createdAt, Comparator.reverseOrder())
      .thenComparing(Memory::id));

    return ordered.size() > limit ? new ArrayList<>(ordered.subList(0, limit)) : ordered;
  }

  @Override
  public List<Memory> findCodeMemoriesBySymbolIds(String profileId, List<String> symbolIds, int limit) {
    if (symbolIds == null || symbolIds.isEmpty() || limit <= 0) {
      return List.of();
    }
    List<String> distinct = symbolIds.stream()
      .filter(s -> s != null && !s.isBlank())
      .distinct()
      .toList();
    if (distinct.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(", ", distinct.stream().map(_ -> "?").toList());
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.addAll(distinct);
    params.add(limit);
    // json_each over payload.$.symbolIds; payload defaults to '{}', so files without the key match
    // zero rows rather than erroring. GROUP BY collapses a memory matched by several symbol ids.
    return jdbc.sql("SELECT m.id, m.session_id, m.type, m.content, m.topic_key, m.supersedes, "
        + "m.superseded, m.payload, m.embed_text, m.created_at "
        + "FROM memories m, json_each(m.payload, '$.symbolIds') je "
        + "WHERE m.profile_id = ? AND m.superseded = 0 AND je.value IN (" + placeholders + ") "
        + "GROUP BY m.id ORDER BY m.created_at DESC LIMIT ?")
      .params(params)
      .query((rs, _) -> mapMemory(rs))
      .list();
  }
}
