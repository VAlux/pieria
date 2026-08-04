package dev.alvo.pieria.storage;

import dev.alvo.pieria.config.DataSourceConfig.VecCapability;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.ContentId;
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
import dev.alvo.pieria.tools.TextSimilarity;
import dev.alvo.pieria.tools.Tokens;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Embedded SQLite backend for {@link MemoryStore}. Hand-written SQL against the V1 schema
 * via Spring's {@link JdbcClient}. Writes are content-addressed and idempotent via
 * {@code INSERT OR IGNORE}; deletes are logical (supersession).
 */
@Repository
public class SqliteMemoryStore implements MemoryStore {

  private static final Logger log = LoggerFactory.getLogger(SqliteMemoryStore.class);

  /**
   * Topic-key namespace owned by the code indexer; exempt from near-duplicate supersession.
   */
  private static final String CODE_TOPIC_NAMESPACE = "code:";

  private static final double DEFAULT_NEAR_DUPLICATE_THRESHOLD =
    Double.parseDouble(PieriaProperties.NEAR_DUPLICATE_THRESHOLD_DEFAULT);

  /**
   * How many FTS candidates to score for near-duplicate supersession. A restatement shares nearly
   * all its content words, so it ranks at the very top; measured against real profiles, the true
   * duplicate was inside the top 10 every time it existed.
   */
  private static final int NEAR_DUPLICATE_CANDIDATES = 10;

  private final JdbcClient jdbc;
  private final VecCapability vecCapability;
  private final boolean vectorEnabled;
  private final double nearDuplicateThreshold;

  /**
   * Production constructor: wires the sqlite-vec capability flag and the retrieval feature switch.
   */
  @org.springframework.beans.factory.annotation.Autowired
  public SqliteMemoryStore(JdbcClient jdbc, VecCapability vecCapability, PieriaProperties properties) {
    this.jdbc = jdbc;
    this.vecCapability = vecCapability;
    this.vectorEnabled = properties.retrieval().vectorEnabled();
    this.nearDuplicateThreshold = properties.ingestion().nearDuplicateThreshold();
  }

  /**
   * Constructor for tests: no sqlite-vec capability (vector search reports unavailable). Keeps the
   * production near-duplicate threshold so supersession behaves as it does in a running daemon.
   */
  public SqliteMemoryStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
    this.vecCapability = null;
    this.vectorEnabled = false;
    this.nearDuplicateThreshold = DEFAULT_NEAR_DUPLICATE_THRESHOLD;
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

      int inserted = jdbc.sql("INSERT OR IGNORE INTO profiles (id, name, created_at) VALUES (?, ?, ?)")
        .params(created.id(), created.name(), created.createdAt().toString())
        .update();
      if (inserted == 1) {
        return created;
      }
      // Another lane created it between our SELECT and INSERT: re-select the winner.
      return findProfile(name).orElseThrow(() ->
        new IllegalStateException("profile was ignored as duplicate but cannot be re-selected: " + name));
    });
  }

  @Override
  @Transactional
  public void deleteProfile(String profileId) {
    // Drop the sqlite-vec index rows first (they are not synchronized by triggers) while the
    // owning memory rows still exist to resolve the sub-select. No-op when vector search is off.
    if (isVectorSearchAvailable()) {
      jdbc.sql("DELETE FROM memories_vec WHERE memory_id IN (SELECT id FROM memories WHERE profile_id = ?)")
        .param(profileId)
        .update();
    }
    // Delete children before parents so the schema's REFERENCES stay satisfiable even if foreign
    // keys are ever enforced. memories/messages deletes fire the FTS-sync triggers automatically.
    jdbc.sql("DELETE FROM vectorization_outbox WHERE memory_id IN (SELECT id FROM memories WHERE profile_id = ?)")
      .param(profileId).update();
    jdbc.sql("DELETE FROM edges WHERE profile_id = ?").param(profileId).update();
    jdbc.sql("DELETE FROM entities WHERE profile_id = ?").param(profileId).update();
    jdbc.sql("DELETE FROM code_edges WHERE profile_id = ?").param(profileId).update();
    jdbc.sql("DELETE FROM code_symbols WHERE profile_id = ?").param(profileId).update();
    jdbc.sql("DELETE FROM code_files WHERE profile_id = ?").param(profileId).update();
    jdbc.sql("DELETE FROM code_modules WHERE profile_id = ?").param(profileId).update();
    jdbc.sql("DELETE FROM memories WHERE profile_id = ?").param(profileId).update();
    jdbc.sql("DELETE FROM messages WHERE profile_id = ?").param(profileId).update();
    jdbc.sql("DELETE FROM profile_config WHERE profile_id = ?").param(profileId).update();
    jdbc.sql("DELETE FROM profile_usage WHERE profile_id = ?").param(profileId).update();
    jdbc.sql("DELETE FROM profile_inference_usage WHERE profile_id = ?").param(profileId).update();
    jdbc.sql("""
      DELETE FROM profile_audit_events
      WHERE profile_id = ? OR profile_name = (SELECT name FROM profiles WHERE id = ?)
      """).params(profileId, profileId).update();
    jdbc.sql("DELETE FROM profiles WHERE id = ?").param(profileId).update();
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
  @Transactional
  public void recordRecallUsage(String profileId, long sourceTokens, long answerTokens) {
    long saved = Math.max(0, sourceTokens - answerTokens);

    jdbc.sql("""
        INSERT INTO profile_usage \
        (profile_id, recall_count, tokens_saved, tokens_recall_served, updated_at) \
        VALUES (?, 1, ?, ?, ?) \
        ON CONFLICT (profile_id) DO UPDATE SET \
        recall_count = recall_count + 1, \
        tokens_saved = tokens_saved + excluded.tokens_saved, \
        tokens_recall_served = tokens_recall_served + excluded.tokens_recall_served, \
        updated_at = excluded.updated_at""")
      .params(profileId, saved, answerTokens, Instant.now().toString())
      .update();
  }

  @Override
  public long sumActiveSourceTokens(String profileId, Collection<String> memoryIds) {
    if (memoryIds == null || memoryIds.isEmpty()) {
      return 0L;
    }
    // Distinct: the same memory can surface from several retrieval channels, and its source must
    // be counted once.
    List<String> ids = memoryIds.stream().distinct().toList();
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));

    List<Object> params = new ArrayList<>(ids.size() + 1);
    params.add(profileId);
    params.addAll(ids);

    Long sum = jdbc.sql("SELECT COALESCE(SUM(source_tokens), 0) FROM memories "
        + "WHERE profile_id = ? AND superseded = 0 AND id IN (" + placeholders + ")")
      .params(params)
      .query(Long.class)
      .single();
    return sum == null ? 0L : sum;
  }

  @Override
  @Transactional
  public void recordIngestUsage(String profileId, long ingestedTokens, long storedTokens) {
    jdbc.sql("""
        INSERT INTO profile_usage \
        (profile_id, ingest_count, tokens_ingested, tokens_stored, updated_at) \
        VALUES (?, 1, ?, ?, ?) \
        ON CONFLICT (profile_id) DO UPDATE SET \
        ingest_count = ingest_count + 1, \
        tokens_ingested = tokens_ingested + excluded.tokens_ingested, \
        tokens_stored = tokens_stored + excluded.tokens_stored, \
        updated_at = excluded.updated_at""")
      .params(profileId, ingestedTokens, storedTokens, Instant.now().toString())
      .update();
  }

  @Override
  public ProfileUsage usageStats(String profileId) {
    return jdbc.sql("""
        SELECT recall_count, ingest_count, tokens_saved, \
        tokens_recall_served, tokens_ingested, tokens_stored \
        FROM profile_usage WHERE profile_id = ?""")
      .param(profileId)
      .query((rs, _) -> new ProfileUsage(
        rs.getLong("recall_count"),
        rs.getLong("ingest_count"),
        rs.getLong("tokens_saved"),
        rs.getLong("tokens_recall_served"),
        rs.getLong("tokens_ingested"),
        rs.getLong("tokens_stored")))
      .optional()
      .orElse(ProfileUsage.empty());
  }

  @Override
  @Transactional
  public void recordInferenceUsage(String profileId, Map<InferenceTier, TierUsage> usage) {
    if (usage == null || usage.isEmpty()) {
      return;
    }
    String now = Instant.now().toString();
    usage.forEach((tier, u) -> jdbc.sql("""
        INSERT INTO profile_inference_usage \
        (profile_id, tier, calls, prompt_tokens, completion_tokens, updated_at) \
        VALUES (?, ?, ?, ?, ?, ?) \
        ON CONFLICT (profile_id, tier) DO UPDATE SET \
        calls = calls + excluded.calls, \
        prompt_tokens = prompt_tokens + excluded.prompt_tokens, \
        completion_tokens = completion_tokens + excluded.completion_tokens, \
        updated_at = excluded.updated_at""")
      .params(profileId, tier.name(), u.calls(), u.promptTokens(), u.completionTokens(), now)
      .update());
  }

  @Override
  public Map<InferenceTier, TierUsage> inferenceUsage(String profileId) {
    return jdbc.sql("""
        SELECT tier, calls, prompt_tokens, completion_tokens \
        FROM profile_inference_usage WHERE profile_id = ?""")
      .param(profileId)
      .query((rs, _) -> Map.entry(
        InferenceTier.valueOf(rs.getString("tier")),
        new TierUsage(rs.getLong("calls"), rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"))))
      .list().stream()
      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
        (a, b) -> b, () -> new EnumMap<>(InferenceTier.class)));
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
  public Map<String, String> ingestLedger(String profileId, String scope) {
    Map<String, String> ledger = new HashMap<>();
    jdbc.sql("SELECT item_key, content_hash FROM ingest_ledger WHERE profile_id = ? AND scope = ?")
      .params(profileId, scope)
      .query((rs, _) -> ledger.put(rs.getString("item_key"), rs.getString("content_hash")))
      .list();
    return ledger;
  }

  @Override
  @Transactional
  public void recordIngestLedger(String profileId, String scope, Map<String, String> hashesByKey) {
    if (hashesByKey == null || hashesByKey.isEmpty()) {
      return;
    }
    String now = Instant.now().toString();
    for (var entry : hashesByKey.entrySet()) {
      jdbc.sql("""
          INSERT INTO ingest_ledger (profile_id, scope, item_key, content_hash, processed_at) \
          VALUES (?, ?, ?, ?, ?) \
          ON CONFLICT (profile_id, scope, item_key) DO UPDATE SET \
          content_hash = excluded.content_hash, processed_at = excluded.processed_at""")
        .params(profileId, scope, entry.getKey(), entry.getValue(), now)
        .update();
    }
  }

  @Override
  @Transactional
  public void insertMessages(String profileId, String sessionId, List<Message> messages) {
    if (messages == null || messages.isEmpty()) {
      return;
    }

    for (Message message : messages) {
      String id = resolveMessageId(profileId, sessionId, message);
      String createdAt = (message.createdAt() == null ? Instant.now() : message.createdAt()).toString();
      int inserted = jdbc.sql("""
          INSERT OR IGNORE INTO messages \
          (id, profile_id, session_id, role, content, created_at) \
          VALUES (?, ?, ?, ?, ?, ?)""")
        .params(id, profileId, sessionId, message.role(), message.content(), createdAt)
        .update();
      if (inserted == 0 && !messageIdOwnedByProfile(id, profileId)) {
        throw new IllegalStateException("message id collision across profiles: " + id);
      }
    }
  }

  /**
   * Keep existing databases idempotent: reuse a legacy unscoped id only when this profile already
   * owns it. Otherwise generate the new profile-scoped id so identical transcripts can coexist.
   */
  private String resolveMessageId(String profileId, String sessionId, Message message) {
    String legacyId = ContentId.forMessage(sessionId, message.role(), message.content());
    if (messageIdOwnedByProfile(legacyId, profileId)) {
      return legacyId;
    }
    return ContentId.forMessage(profileId, sessionId, message.role(), message.content());
  }

  @Override
  @Transactional
  public Memory insertMemory(String profileId, Memory memory) {
    return insertMemoryRecord(profileId, memory, Tokens.estimate(memory.content())).stored();
  }

  private MemoryInsert insertMemoryRecord(String profileId, Memory memory, long sourceTokens) {
    String id = resolveMemoryId(profileId, memory);
    Instant createdAt = memory.createdAt() == null ? Instant.now() : memory.createdAt();
    String payload = memory.payload() == null ? "{}" : memory.payload();

    int inserted = jdbc.sql("""
        INSERT OR IGNORE INTO memories \
        (id, profile_id, session_id, type, content, topic_key, supersedes, \
        superseded, payload, embed_text, created_at, source_tokens) \
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
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
        createdAt.toString(),
        Math.max(0, sourceTokens))
      .update();

    if (inserted == 0) {
      Memory existing = memoryByIdAndProfile(id, profileId).orElseThrow(() ->
        new IllegalStateException("memory id collision across profiles: " + id));
      return new MemoryInsert(existing, false);
    }

    return new MemoryInsert(new Memory(
      id,
      memory.sessionId(),
      memory.type(),
      memory.content(),
      memory.topicKey(),
      memory.supersedes(),
      memory.superseded(),
      payload,
      memory.embedText(),
      createdAt), true);
  }

  /**
   * Reuse an active legacy unscoped id only when this profile owns it; this preserves idempotency
   * for pre-upgrade data without allowing another profile's row to absorb the new write.
   */
  private String resolveMemoryId(String profileId, Memory memory) {
    if (memory.id() != null) {
      return memory.id();
    }
    String legacyId = ContentId.forMemory(memory.sessionId(), memory.type(), memory.content());
    if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM memories WHERE id = ? AND profile_id = ? AND superseded = 0)")
      .params(legacyId, profileId)
      .query(Integer.class)
      .single() == 1) {
      return legacyId;
    }
    return ContentId.forMemory(profileId, memory.sessionId(), memory.type(), memory.content());
  }

  private Optional<Memory> memoryByIdAndProfile(String memoryId, String profileId) {
    return jdbc.sql(
        """
          SELECT id, session_id, type, content, topic_key, supersedes, superseded, payload, embed_text, created_at \
          FROM memories WHERE id = ? AND profile_id = ?""")
      .params(memoryId, profileId)
      .query((rs, _) -> mapMemory(rs))
      .optional();
  }

  private boolean messageIdOwnedByProfile(String id, String profileId) {
    return jdbc.sql("SELECT EXISTS(SELECT 1 FROM messages WHERE id = ? AND profile_id = ?)")
      .params(id, profileId)
      .query(Integer.class)
      .single() == 1;
  }

  @Override
  @Transactional
  public StoreOutcome store(String profileId, Memory memory, GraphFragment graph, long sourceTokens) {
    String id = resolveMemoryId(profileId, memory);

    // EVENT and TASK are append-only; only FACT/INSTRUCTION supersede a predecessor.
    boolean supersedable =
      memory.type() == MemoryType.FACT || memory.type() == MemoryType.INSTRUCTION;

    String supersededId = null;
    if (supersedable && memory.topicKey() != null) {
      supersededId = activeIdByTopicKey(profileId, memory, id);
    }
    if (supersededId == null && supersedable) {
      supersededId = activeIdByNearDuplicateContent(profileId, memory, id);
    }
    if (supersededId != null) {
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

    MemoryInsert insert = insertMemoryRecord(profileId, toInsert, sourceTokens);
    Memory stored = insert.stored();

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

    return new StoreOutcome(stored, supersededId, enqueuedVector, insert.inserted());
  }

  private record MemoryInsert(Memory stored, boolean inserted) {
  }

  /**
   * The active memory this one supersedes by exact topic key, or null. Returns null when the active
   * row IS the incoming memory (identical content-addressed id): a re-ingest must stay idempotent,
   * not supersede the row it would re-insert.
   */
  private String activeIdByTopicKey(String profileId, Memory memory, String id) {
    return jdbc.sql(
        """
          SELECT id FROM memories \
          WHERE profile_id = ? AND type = ? AND topic_key = ? AND superseded = 0 \
          ORDER BY created_at DESC LIMIT 1""")
      .params(profileId, memory.type().wire(), memory.topicKey())
      .query(String.class)
      .optional()
      .filter(activeId -> !activeId.equals(id))
      .orElse(null);
  }

  /**
   * The active memory this one restates in different words, or null — the fallback for when the
   * extractor gave the same fact a drifted topic key.
   *
   * <p>Code-derived memories are exempt. Their topic key is the file path, which is exact and
   * stable, so they never drift; and their content is templated, which makes two summaries of
   * <em>different</em> files score as near-identical. They are the one population where this check
   * produces false positives, and the one that does not need it.
   */
  private String activeIdByNearDuplicateContent(String profileId, Memory memory, String id) {
    if (nearDuplicateThreshold <= 0.0 || isCodeDerived(memory.topicKey())) {
      return null;
    }
    Set<String> incoming = TextSimilarity.shingles(memory.content());
    if (incoming.isEmpty()) {
      return null;
    }
    String best = null;
    double bestScore = nearDuplicateThreshold;
    for (Memory candidate : findNearDuplicateCandidates(
      profileId, memory.type(), memory.content(), NEAR_DUPLICATE_CANDIDATES)) {
      if (candidate.id().equals(id) || isCodeDerived(candidate.topicKey())) {
        continue;
      }
      double score = TextSimilarity.jaccard(incoming, TextSimilarity.shingles(candidate.content()));
      if (score >= bestScore) {
        bestScore = score;
        best = candidate.id();
      }
    }
    if (best != null) {
      log.debug("near-duplicate supersession profile={} newId={} supersedes={} similarity={}",
        profileId, id, best, bestScore);
    }
    return best;
  }

  private static boolean isCodeDerived(String topicKey) {
    return topicKey != null && topicKey.startsWith(CODE_TOPIC_NAMESPACE);
  }

  @Override
  public List<Memory> findNearDuplicateCandidates(
    String profileId, MemoryType type, String content, int limit) {
    String match = toFtsMatch(content);
    if (match == null) {
      return List.of();
    }
    return jdbc.sql(
        """
          SELECT m.id, m.session_id, m.type, m.content, m.topic_key, m.supersedes, m.superseded, \
          m.payload, m.embed_text, m.created_at \
          FROM memories_fts f \
          JOIN memories m ON m.rowid = f.rowid \
          WHERE memories_fts MATCH ? AND m.profile_id = ? AND m.type = ? AND m.superseded = 0 \
          ORDER BY f.rank LIMIT ?""")
      .params(match, profileId, type.wire(), limit)
      .query((rs, _) -> mapMemory(rs))
      .list();
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
  public List<Memory> listMemories(String profileId, MemoryType typeFilter, String sessionFilter,
                                   boolean includeSuperseded) {
    StringBuilder sql = new StringBuilder(
      """
        SELECT id, session_id, type, content, topic_key, supersedes, superseded, payload, embed_text, created_at \
        FROM memories \
        WHERE profile_id = ?""");
    if (!includeSuperseded) {
      sql.append(" AND superseded = 0");
    }

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

  /**
   * Edge columns qualified with the {@code e} alias — {@code edges} and {@code memories} share
   * {@code id}, {@code profile_id} and {@code created_at}, so any query joining both must qualify.
   */
  private static final String EDGE_COLUMNS =
    "e.id, e.profile_id, e.source_entity_id, e.target_entity_id, e.relation, e.memory_id, e.created_at";

  private static Entity mapEntity(ResultSet rs) throws SQLException {
    return new Entity(
      rs.getString("id"),
      rs.getString("profile_id"),
      rs.getString("type"),
      rs.getString("name"),
      rs.getString("payload"),
      Instant.parse(rs.getString("created_at")));
  }

  private static Edge mapEdge(ResultSet rs) throws SQLException {
    return new Edge(
      rs.getString("id"),
      rs.getString("profile_id"),
      rs.getString("source_entity_id"),
      rs.getString("target_entity_id"),
      rs.getString("relation"),
      rs.getString("memory_id"),
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
  public List<Memory> findGraphOrphans(String profileId, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    return jdbc.sql("""
        SELECT id, session_id, type, content, topic_key, supersedes, superseded, payload, embed_text, created_at \
        FROM memories m \
        WHERE m.profile_id = ? AND m.superseded = 0 AND m.type != ? AND m.graph_adopted_at IS NULL \
        AND NOT EXISTS (SELECT 1 FROM edges e WHERE e.memory_id = m.id) \
        ORDER BY m.created_at ASC \
        LIMIT ?""")
      .params(profileId, MemoryType.TASK.wire(), limit)
      .query((rs, _) -> mapMemory(rs))
      .list();
  }

  @Override
  public long countGraphOrphans(String profileId) {
    Long count = jdbc.sql("""
        SELECT COUNT(*) FROM memories m \
        WHERE m.profile_id = ? AND m.superseded = 0 AND m.type != ? AND m.graph_adopted_at IS NULL \
        AND NOT EXISTS (SELECT 1 FROM edges e WHERE e.memory_id = m.id)""")
      .params(profileId, MemoryType.TASK.wire())
      .query(Long.class)
      .single();
    return count == null ? 0L : count;
  }

  @Override
  public List<Memory> findGraphOrphans(String profileId, List<String> sessionIds, int limit) {
    if (limit <= 0 || sessionIds == null || sessionIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(sessionIds.size(), "?"));
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.add(MemoryType.TASK.wire());
    params.addAll(sessionIds);
    params.add(limit);
    return jdbc.sql("""
        SELECT id, session_id, type, content, topic_key, supersedes, superseded, payload, embed_text, created_at
        FROM memories m
        WHERE m.profile_id = ? AND m.superseded = 0 AND m.type != ? AND m.graph_adopted_at IS NULL
        AND m.session_id IN (%s)
        AND NOT EXISTS (SELECT 1 FROM edges e WHERE e.memory_id = m.id)
        ORDER BY m.created_at ASC
        LIMIT ?""".formatted(placeholders))
      .params(params)
      .query((rs, _) -> mapMemory(rs))
      .list();
  }

  @Override
  public long countGraphOrphans(String profileId, List<String> sessionIds) {
    if (sessionIds == null || sessionIds.isEmpty()) {
      return 0L;
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(sessionIds.size(), "?"));
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.add(MemoryType.TASK.wire());
    params.addAll(sessionIds);
    Long count = jdbc.sql("""
        SELECT COUNT(*) FROM memories m
        WHERE m.profile_id = ? AND m.superseded = 0 AND m.type != ? AND m.graph_adopted_at IS NULL
        AND m.session_id IN (%s)
        AND NOT EXISTS (SELECT 1 FROM edges e WHERE e.memory_id = m.id)""".formatted(placeholders))
      .params(params)
      .query(Long.class)
      .single();
    return count == null ? 0L : count;
  }

  @Override
  @Transactional
  public void attachGraph(String profileId, String memoryId, GraphFragment graph) {
    persistGraph(profileId, memoryId, graph);
    markGraphAdopted(profileId, memoryId);
  }

  @Override
  public void markGraphAdopted(String profileId, String memoryId) {
    jdbc.sql("UPDATE memories SET graph_adopted_at = ? WHERE id = ? AND profile_id = ?")
      .params(Instant.now().toString(), memoryId, profileId)
      .update();
  }

  @Override
  public boolean isGraphAdopted(String profileId, String memoryId) {
    Long adopted = jdbc.sql("""
        SELECT COUNT(*) FROM memories \
        WHERE id = ? AND profile_id = ? AND graph_adopted_at IS NOT NULL""")
      .params(memoryId, profileId)
      .query(Long.class)
      .single();
    return adopted != null && adopted > 0;
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
    return activeNeighbors(profileId, frontier, fanout, List.of());
  }

  /**
   * As above, but keeping only neighbours whose entity type is in {@code types} ({@code types}
   * empty means no type restriction). The filter is applied in SQL so {@code fanout} bounds the
   * neighbours the caller actually wants rather than being spent on filtered-out rows.
   */
  private List<String> activeNeighbors(String profileId, List<String> frontier, int fanout,
                                       List<String> types) {
    if (frontier.isEmpty() || fanout <= 0) {
      return List.of();
    }
    // One branch per direction, each an index-friendly equality lookup on the frontier. A single
    // OR'd predicate would defeat idx_edge_source / idx_edge_target.
    String outgoing = neighborBranch("e.source_entity_id", "e.target_entity_id", frontier, types);
    String incoming = neighborBranch("e.target_entity_id", "e.source_entity_id", frontier, types);

    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.addAll(frontier);
    params.addAll(types);
    params.add(profileId);
    params.addAll(frontier);
    params.addAll(types);
    params.add(fanout);

    List<String> rows = jdbc.sql("SELECT neighbor FROM ( " + outgoing + " UNION ALL " + incoming
        + " ) ORDER BY ca DESC, neighbor ASC LIMIT ?")
      .params(params)
      .query(String.class)
      .list();
    return List.copyOf(new LinkedHashSet<>(rows));
  }

  /**
   * One direction of the neighbour lookup: match the frontier on {@code fromColumn}, return the
   * entity on {@code toColumn}. Parameters bind in the order (profileId, frontier…, types…).
   */
  private static String neighborBranch(String fromColumn, String toColumn,
                                       List<String> frontier, List<String> types) {
    String frontierParams = String.join(", ", frontier.stream().map(_ -> "?").toList());
    String typeJoin = types.isEmpty() ? ""
      : " JOIN entities en ON en.id = " + toColumn;
    String typeFilter = types.isEmpty() ? ""
      : " AND en.type IN (" + String.join(", ", types.stream().map(_ -> "?").toList()) + ")";
    return "SELECT " + toColumn + " AS neighbor, e.created_at AS ca FROM edges e "
      + "JOIN memories m ON m.id = e.memory_id" + typeJoin + " "
      + "WHERE e.profile_id = ? AND m.superseded = 0 AND " + fromColumn + " IN (" + frontierParams + ")"
      + typeFilter;
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
    // json_each over payload.$.symbolIds; any valid JSON without the key matches zero rows. Model-
    // written payloads are not guaranteed to be JSON at all, and json_each aborts the whole
    // statement on a malformed one, so substitute '{}' for those rows. The guard has to live in the
    // json_each argument rather than the WHERE clause: a WHERE predicate only filters rows json_each
    // has already been stepped over. GROUP BY collapses a memory matched by several symbol ids.
    return jdbc.sql("SELECT m.id, m.session_id, m.type, m.content, m.topic_key, m.supersedes, "
        + "m.superseded, m.payload, m.embed_text, m.created_at "
        + "FROM memories m, "
        + "json_each(CASE WHEN json_valid(m.payload) THEN m.payload ELSE '{}' END, '$.symbolIds') je "
        + "WHERE m.profile_id = ? AND m.superseded = 0 AND je.value IN (" + placeholders + ") "
        + "GROUP BY m.id ORDER BY m.created_at DESC LIMIT ?")
      .params(params)
      .query((rs, _) -> mapMemory(rs))
      .list();
  }

  // ---- graph explorer reads ------------------------------------------------------------------
  //
  // Every query below reaches the edges table through an equality predicate that idx_edge_source or
  // idx_edge_target can serve. Correlating entities and edges with a single
  // `ON (source = id OR target = id)` predicate instead defeats both indexes and degrades to a full
  // memories scan per entity — on a 45k-edge profile that is the difference between 60ms and 180s.

  /**
   * The ids of every entity an active edge touches, as a subquery. Binds profileId twice.
   */
  private static final String CONNECTED_ENTITY_IDS = """
    SELECT e.source_entity_id AS eid FROM edges e JOIN memories m ON m.id = e.memory_id \
      WHERE e.profile_id = ? AND m.superseded = 0 \
    UNION \
    SELECT e.target_entity_id FROM edges e JOIN memories m ON m.id = e.memory_id \
      WHERE e.profile_id = ? AND m.superseded = 0""";

  @Override
  public GraphCounts graphCounts(String profileId) {
    int edges = jdbc.sql("SELECT COUNT(*) FROM edges e JOIN memories m ON m.id = e.memory_id "
        + "WHERE e.profile_id = ? AND m.superseded = 0")
      .param(profileId)
      .query(Integer.class)
      .single();

    int entities = jdbc.sql("SELECT COUNT(*) FROM entities en WHERE en.profile_id = ? "
        + "AND en.id IN (" + CONNECTED_ENTITY_IDS + ")")
      .params(profileId, profileId, profileId)
      .query(Integer.class)
      .single();

    return new GraphCounts(entities, edges);
  }

  @Override
  public Map<String, Integer> entityTypeCounts(String profileId) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    jdbc.sql("SELECT type, COUNT(*) AS c FROM entities WHERE profile_id = ? "
        + "GROUP BY type ORDER BY c DESC, type ASC")
      .param(profileId)
      .query((rs, _) -> Map.entry(rs.getString("type"), rs.getInt("c")))
      .list()
      .forEach(e -> counts.put(e.getKey(), e.getValue()));
    return counts;
  }

  @Override
  public List<RankedEntity> topEntitiesByDegree(String profileId, List<String> types, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    List<String> typeFilter = cleaned(types);
    String typeClause = typeFilter.isEmpty() ? ""
      : " AND en.type IN (" + String.join(", ", typeFilter.stream().map(_ -> "?").toList()) + ")";

    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.add(profileId);
    params.add(profileId);
    params.addAll(typeFilter);
    params.add(limit);

    return jdbc.sql("SELECT en.id, en.profile_id, en.type, en.name, en.payload, en.created_at, d.deg "
        + "FROM (SELECT eid, COUNT(*) AS deg FROM (" + degreeRows() + ") GROUP BY eid) d "
        + "JOIN entities en ON en.id = d.eid "
        + "WHERE en.profile_id = ?" + typeClause + " "
        + "ORDER BY d.deg DESC, en.name ASC LIMIT ?")
      .params(params)
      .query((rs, _) -> new RankedEntity(mapEntity(rs), rs.getInt("deg")))
      .list();
  }

  @Override
  public List<RankedEntity> searchEntities(String profileId, String query, List<String> types, int limit) {
    if (query == null || query.isBlank() || limit <= 0) {
      return List.of();
    }
    List<String> typeFilter = cleaned(types);
    String typeClause = typeFilter.isEmpty() ? ""
      : " AND type IN (" + String.join(", ", typeFilter.stream().map(_ -> "?").toList()) + ")";

    // Names are stored already normalized (lowercased, collapsed) and SQLite's LIKE is
    // case-insensitive for ASCII, so a bare substring match is the right lookup here.
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.add("%" + escapeLike(query.trim()) + "%");
    params.addAll(typeFilter);
    // Match on name (indexed prefix scan degrades to a profile-scoped scan for infix matches), then
    // rank the shortlist by degree. Over-fetch so the degree ranking has something to choose from
    // rather than just returning the alphabetically-first `limit` matches.
    params.add(Math.min(limit * 5, 500));

    List<Entity> matches = jdbc.sql("SELECT " + ENTITY_COLUMNS + " FROM entities "
        + "WHERE profile_id = ? AND name LIKE ? ESCAPE '\\'" + typeClause + " "
        + "ORDER BY LENGTH(name) ASC, name ASC LIMIT ?")
      .params(params)
      .query((rs, _) -> mapEntity(rs))
      .list();
    if (matches.isEmpty()) {
      return List.of();
    }

    Map<String, Integer> degrees = entityDegrees(profileId, matches.stream().map(Entity::id).toList());
    return matches.stream()
      .map(e -> new RankedEntity(e, degrees.getOrDefault(e.id(), 0)))
      .sorted(Comparator.comparingInt(RankedEntity::degree).reversed()
        .thenComparing(r -> r.entity().name()))
      .limit(limit)
      .toList();
  }

  @Override
  public List<NeighborHop> graphNeighborhood(String profileId, String seedEntityId, int depth,
                                             List<String> types, int fanout) {
    if (seedEntityId == null || seedEntityId.isBlank()) {
      return List.of();
    }
    List<String> typeFilter = cleaned(types);
    List<NeighborHop> result = new ArrayList<>();
    LinkedHashSet<String> visited = new LinkedHashSet<>();
    visited.add(seedEntityId);
    result.add(new NeighborHop(seedEntityId, 0));

    List<String> frontier = List.of(seedEntityId);
    for (int hop = 1; hop <= Math.max(0, depth) && !frontier.isEmpty(); hop++) {
      List<String> next = new ArrayList<>();
      for (String neighbor : activeNeighbors(profileId, frontier, fanout, typeFilter)) {
        if (visited.add(neighbor)) {
          next.add(neighbor);
          result.add(new NeighborHop(neighbor, hop));
        }
      }
      frontier = next;
    }
    return List.copyOf(result);
  }

  @Override
  public List<Edge> inducedEdges(String profileId, List<String> entityIds) {
    List<String> distinct = cleaned(entityIds);
    if (distinct.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(", ", distinct.stream().map(_ -> "?").toList());
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.addAll(distinct);
    params.addAll(distinct);

    return jdbc.sql("SELECT " + EDGE_COLUMNS + " "
        + "FROM edges e JOIN memories m ON m.id = e.memory_id "
        + "WHERE e.profile_id = ? AND m.superseded = 0 "
        + "AND e.source_entity_id IN (" + placeholders + ") "
        + "AND e.target_entity_id IN (" + placeholders + ") "
        + "ORDER BY e.created_at DESC, e.id ASC")
      .params(params)
      .query((rs, _) -> mapEdge(rs))
      .list();
  }

  @Override
  public List<Entity> findEntitiesByIds(String profileId, List<String> entityIds) {
    List<String> distinct = cleaned(entityIds);
    if (distinct.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(", ", distinct.stream().map(_ -> "?").toList());
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.addAll(distinct);

    return jdbc.sql("SELECT " + ENTITY_COLUMNS + " FROM entities "
        + "WHERE profile_id = ? AND id IN (" + placeholders + ")")
      .params(params)
      .query((rs, _) -> mapEntity(rs))
      .list();
  }

  @Override
  public Map<String, Integer> entityDegrees(String profileId, List<String> entityIds) {
    List<String> distinct = cleaned(entityIds);
    if (distinct.isEmpty()) {
      return Map.of();
    }
    String placeholders = String.join(", ", distinct.stream().map(_ -> "?").toList());
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.addAll(distinct);
    params.add(profileId);
    params.addAll(distinct);

    Map<String, Integer> degrees = new HashMap<>();
    jdbc.sql("SELECT eid, COUNT(*) AS deg FROM ( "
        + "  SELECT e.source_entity_id AS eid FROM edges e JOIN memories m ON m.id = e.memory_id "
        + "    WHERE e.profile_id = ? AND m.superseded = 0 "
        + "    AND e.source_entity_id IN (" + placeholders + ") "
        + "  UNION ALL "
        + "  SELECT e.target_entity_id FROM edges e JOIN memories m ON m.id = e.memory_id "
        + "    WHERE e.profile_id = ? AND m.superseded = 0 "
        + "    AND e.target_entity_id IN (" + placeholders + ") "
        + ") GROUP BY eid")
      .params(params)
      .query((rs, _) -> Map.entry(rs.getString("eid"), rs.getInt("deg")))
      .list()
      .forEach(e -> degrees.put(e.getKey(), e.getValue()));
    return degrees;
  }

  @Override
  public List<IncidentEdge> incidentEdges(String profileId, String entityId, int limit) {
    if (entityId == null || entityId.isBlank() || limit <= 0) {
      return List.of();
    }
    // Two indexed equality branches rather than one OR'd predicate, then the far-end entity joined
    // on the id the branch already produced.
    return jdbc.sql("SELECT x.id, x.profile_id, x.source_entity_id, x.target_entity_id, "
        + "x.relation, x.memory_id, x.created_at, x.outgoing, "
        + "o.id AS o_id, o.profile_id AS o_profile_id, o.type AS o_type, o.name AS o_name, "
        + "o.payload AS o_payload, o.created_at AS o_created_at "
        + "FROM ( "
        + "  SELECT " + EDGE_COLUMNS + ", 1 AS outgoing, e.target_entity_id AS other_id "
        + "    FROM edges e JOIN memories m ON m.id = e.memory_id "
        + "    WHERE e.profile_id = ? AND m.superseded = 0 AND e.source_entity_id = ? "
        + "  UNION ALL "
        + "  SELECT " + EDGE_COLUMNS + ", 0 AS outgoing, e.source_entity_id AS other_id "
        + "    FROM edges e JOIN memories m ON m.id = e.memory_id "
        + "    WHERE e.profile_id = ? AND m.superseded = 0 AND e.target_entity_id = ? "
        + ") x JOIN entities o ON o.id = x.other_id "
        + "ORDER BY x.created_at DESC, x.id ASC LIMIT ?")
      .params(profileId, entityId, profileId, entityId, limit)
      .query((rs, _) -> new IncidentEdge(
        mapEdge(rs),
        new Entity(
          rs.getString("o_id"),
          rs.getString("o_profile_id"),
          rs.getString("o_type"),
          rs.getString("o_name"),
          rs.getString("o_payload"),
          Instant.parse(rs.getString("o_created_at"))),
        rs.getInt("outgoing") == 1))
      .list();
  }

  /**
   * Degree source rows: every active-edge endpoint, once per incidence. Binds profileId twice.
   */
  private static String degreeRows() {
    return """
      SELECT e.source_entity_id AS eid FROM edges e JOIN memories m ON m.id = e.memory_id \
        WHERE e.profile_id = ? AND m.superseded = 0 \
      UNION ALL \
      SELECT e.target_entity_id FROM edges e JOIN memories m ON m.id = e.memory_id \
        WHERE e.profile_id = ? AND m.superseded = 0""";
  }

  /**
   * Null-safe, blank-free, order-preserving de-duplication for id / type parameter lists.
   */
  private static List<String> cleaned(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return values.stream().filter(v -> v != null && !v.isBlank()).distinct().toList();
  }

  /**
   * Escape LIKE wildcards so a user's {@code %} or {@code _} matches literally.
   */
  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
