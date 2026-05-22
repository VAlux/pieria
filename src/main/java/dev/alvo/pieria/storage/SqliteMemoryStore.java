package dev.alvo.pieria.storage;

import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.Profile;
import dev.alvo.pieria.domain.RecallCandidate;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

  private final JdbcClient jdbc;

  public SqliteMemoryStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
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
