package dev.alvo.pieria.storage;

import dev.alvo.pieria.api.response.RevisionResponse;
import dev.alvo.pieria.api.response.TemporalValidity;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryTimes;
import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
import dev.alvo.pieria.domain.temporal.ValidityResolver;
import dev.alvo.pieria.tools.Hash;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static dev.alvo.pieria.domain.temporal.TemporalSnapshot.timestamp;

/** SQL temporal persistence, invoked inside the owning MemoryStore write transaction. */
final class SqliteTemporalStore {
  private final JdbcClient jdbc;
  SqliteTemporalStore(JdbcClient jdbc) { this.jdbc = jdbc; }

  TemporalSnapshot snapshot(String asOf, String knownAsOf) {
    Instant now = Instant.now();
    long sequence = jdbc.sql("SELECT COALESCE(MAX(sequence), 0) FROM temporal_transactions")
      .query(Long.class).single();
    return TemporalSnapshot.resolve(asOf, knownAsOf, now, sequence);
  }

  record Transaction(long sequence, Instant at) {}
  Transaction transaction() {
    String previous = jdbc.sql("SELECT recorded_at FROM temporal_transactions ORDER BY sequence DESC LIMIT 1")
      .query(String.class).optional().orElse(null);
    Instant at = Instant.now();
    if (previous != null && !at.isAfter(Instant.parse(previous))) at = Instant.parse(previous).plusNanos(1);
    jdbc.sql("INSERT INTO temporal_transactions(recorded_at) VALUES (?)").param(timestamp(at)).update();
    long sequence = jdbc.sql("SELECT MAX(sequence) FROM temporal_transactions").query(Long.class).single();
    return new Transaction(sequence, at);
  }

  boolean append(String profile, Memory memory, Memory source, Instant explicitFrom, Instant explicitTo,
                 String occurrence, String predecessor) {
    Instant stated = MemoryTimes.knowledgeTime(new Memory(null, null, source.type(), source.content(),
      null, null, false, source.payload(), null, null));
    String identity = Hash.hash128(profile, memory.id(), source.topicKey() == null ? "" : source.topicKey(),
      occurrence == null ? "" : occurrence, occurrence != null || stated == null ? "" : timestamp(stated),
      explicitFrom == null ? "" : timestamp(explicitFrom), explicitTo == null ? "" : timestamp(explicitTo));
    if (jdbc.sql("SELECT COUNT(*) FROM memory_assertions WHERE id = ?").param(identity).query(Integer.class).single() > 0)
      return false;
    if (jdbc.sql("SELECT forgotten FROM memories WHERE id = ?").param(memory.id()).query(Integer.class).single() != 0)
      return false;
    Instant from = explicitFrom != null ? explicitFrom : stated != null ? stated : memory.createdAt();
    new ValidityResolver.Assertion(identity, from, explicitTo, stated, identity, 0);
    boolean state = source.type().wire().equals("fact") || source.type().wire().equals("instruction")
      || (source.topicKey() != null && source.topicKey().startsWith("trace:outcome:"));
    String group = state && source.topicKey() != null ? source.type().wire() + ":" + source.topicKey() : "assertion:" + identity;
    if (state && source.topicKey() != null) {
      group = jdbc.sql("SELECT a.fact_group FROM memory_assertions a JOIN assertion_revisions r ON r.assertion_id = a.id "
        + "JOIN memories m ON m.id = r.memory_id WHERE a.profile_id = ? AND m.type = ? AND m.topic_key = ? "
        + "ORDER BY r.from_sequence DESC, a.id LIMIT 1")
        .params(profile, source.type().wire(), source.topicKey()).query(String.class).optional().orElse(group);
    }
    if (predecessor != null) {
      group = jdbc.sql("SELECT a.fact_group FROM memory_assertions a JOIN assertion_revisions r ON r.assertion_id = a.id WHERE r.memory_id = ? LIMIT 1")
        .param(predecessor).query(String.class).optional().orElse(group);
    }
    RevisionResponse target = null;
    if (source.conflict() != null) {
      String targetMemory = jdbc.sql("SELECT r.memory_id FROM assertion_revisions r JOIN memory_assertions a ON a.id = r.assertion_id "
          + "JOIN memories m ON m.id = r.memory_id WHERE r.id = ? AND r.to_sequence IS NULL AND r.invalidated = 0 AND a.profile_id = ? AND m.forgotten = 0")
        .params(source.conflict().revisionId(), profile).query(String.class).optional().orElse(null);
      if (targetMemory != null) {
        target = history(profile, targetMemory).stream().filter(r -> r.revisionId().equals(source.conflict().revisionId())).findFirst().orElse(null);
        group = jdbc.sql("SELECT fact_group FROM memory_assertions WHERE id = ?").param(target.assertionId()).query(String.class).single();
        if ("correction".equals(source.conflict().relationship()) && explicitFrom == null) from = target.validFrom();
      }
    }
    Transaction tx = transaction();
    jdbc.sql("INSERT INTO memory_assertions(id, profile_id, source_occurrence, fact_group) VALUES (?, ?, ?, ?)")
      .params(identity, profile, occurrence == null ? identity : occurrence, group).update();
    if (target != null && "correction".equals(source.conflict().relationship())) {
      jdbc.sql("UPDATE assertion_revisions SET recorded_to = ?, to_sequence = ? WHERE id = ? AND to_sequence IS NULL")
        .params(timestamp(tx.at()), tx.sequence(), target.revisionId()).update();
      insertRevision(identity + ":corrected", target.assertionId(), target.memoryId(), target.validFrom(), target.validTo(),
        target.statedAt(), tx, target.revisionId(), true);
    }
    insertRevision(identity + ":0", identity, memory.id(), from, explicitTo, stated, tx,
      target == null ? null : target.revisionId(), false);
    jdbc.sql("UPDATE assertion_revisions SET source_payload = ?, conflict_evidence = ? WHERE id = ?")
      .params(source.payload(), target == null ? null : new ObjectMapper().writeValueAsString(source.conflict()), identity + ":0").update();
    resolve(profile, group, tx);
    return true;
  }

  void insertRevision(String id, String assertion, String memory, Instant from, Instant to, Instant stated,
                      Transaction tx, String corrects, boolean invalidated) {
    jdbc.sql("""
      INSERT INTO assertion_revisions(id, assertion_id, memory_id, valid_from, valid_to, stated_at,
        recorded_from, from_sequence, corrects, invalidated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """).params(id, assertion, memory, timestamp(from), timestamp(to), timestamp(stated), timestamp(tx.at()),
        tx.sequence(), corrects, invalidated ? 1 : 0).update();
  }

  void resolve(String profile, String group, Transaction tx) {
    List<ValidityResolver.Assertion> assertions = jdbc.sql("""
      SELECT r.*, a.source_occurrence FROM assertion_revisions r
      JOIN memory_assertions a ON a.id = r.assertion_id
      WHERE a.profile_id = ? AND a.fact_group = ? AND r.to_sequence IS NULL AND r.invalidated = 0
      """).params(profile, group).query((rs, _) -> new ValidityResolver.Assertion(rs.getString("id"),
        Instant.parse(rs.getString("valid_from")), instant(rs.getString("valid_to")), instant(rs.getString("stated_at")),
        rs.getString("source_occurrence"), rs.getLong("from_sequence"))).list();
    // Forgotten assertions still participate in resolution: forgetting hides evidence, never restores competitors.
    jdbc.sql("UPDATE validity_segments SET recorded_to = ?, to_sequence = ? WHERE profile_id = ? AND fact_group = ? AND to_sequence IS NULL")
      .params(timestamp(tx.at()), tx.sequence(), profile, group).update();
    for (ValidityResolver.Segment segment : ValidityResolver.resolve(assertions)) {
      jdbc.sql("""
        INSERT INTO validity_segments(revision_id, profile_id, fact_group, valid_from, valid_to, recorded_from, from_sequence)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """).params(segment.revisionId(), profile, group, timestamp(segment.from()), timestamp(segment.to()), timestamp(tx.at()), tx.sequence()).update();
    }
  }

  static Instant instant(String value) { return value == null ? null : Instant.parse(value); }

  /** Values originate only from parsed instants and a database integer, never caller SQL text. */
  static String visible(String memoryAlias, TemporalSnapshot snapshot) {
    String m = memoryAlias.isEmpty() ? "memories" : memoryAlias;
    String world = "'" + timestamp(snapshot.asOf()) + "'";
    String known = "'" + timestamp(snapshot.knownAsOf()) + "'";
    return m + ".forgotten = 0 AND EXISTS (SELECT 1 FROM assertion_revisions ar INDEXED BY idx_revision_memory CROSS JOIN validity_segments vs INDEXED BY idx_segment_revision ON ar.id = vs.revision_id "
      + "WHERE ar.memory_id = " + m + ".id AND vs.profile_id = " + m + ".profile_id AND vs.valid_from <= " + world
      + " AND (vs.valid_to IS NULL OR " + world + " < vs.valid_to) AND vs.recorded_from <= " + known
      + " AND vs.from_sequence <= " + snapshot.sequence()
      + " AND (vs.recorded_to IS NULL OR " + known + " < vs.recorded_to OR vs.to_sequence > " + snapshot.sequence() + "))";
  }

  List<TemporalValidity> selected(String memory, TemporalSnapshot snapshot) {
    return jdbc.sql("""
      SELECT a.id AS assertion_id, r.id AS revision_id, s.*, r.stated_at, a.source_occurrence, a.fact_group, r.source_payload FROM validity_segments s
      JOIN assertion_revisions r ON r.id = s.revision_id JOIN memory_assertions a ON a.id = r.assertion_id
      JOIN memories m ON m.id = r.memory_id WHERE m.id = ? AND m.forgotten = 0
      AND s.valid_from <= ? AND (s.valid_to IS NULL OR ? < s.valid_to)
      AND s.recorded_from <= ? AND s.from_sequence <= ?
      AND (s.recorded_to IS NULL OR ? < s.recorded_to OR s.to_sequence > ?)
      """).params(memory, timestamp(snapshot.asOf()), timestamp(snapshot.asOf()), timestamp(snapshot.knownAsOf()), snapshot.sequence(),
        timestamp(snapshot.knownAsOf()), snapshot.sequence()).query((rs, _) -> new TemporalValidity(rs.getString("assertion_id"),
          rs.getString("revision_id"), instant(rs.getString("valid_from")), instant(rs.getString("valid_to")),
          instant(rs.getString("recorded_from")), null,
          instant(rs.getString("stated_at")), rs.getString("source_occurrence"), rs.getString("fact_group"), rs.getString("source_payload"))).list();
  }

  List<TemporalValidity> segments(String profile, String memory) {
    return jdbc.sql("""
      SELECT a.id AS assertion_id, r.id AS revision_id, s.*, r.stated_at,
        a.source_occurrence, a.fact_group, r.source_payload
      FROM assertion_revisions r JOIN validity_segments s ON s.revision_id = r.id
      JOIN memory_assertions a ON a.id = r.assertion_id JOIN memories m ON m.id = r.memory_id
      WHERE a.profile_id = ? AND m.id = ? AND m.forgotten = 0 ORDER BY s.from_sequence, s.valid_from
      """).params(profile, memory).query((rs, _) -> new TemporalValidity(rs.getString("assertion_id"),
        rs.getString("revision_id"), instant(rs.getString("valid_from")), instant(rs.getString("valid_to")),
        instant(rs.getString("recorded_from")), instant(rs.getString("recorded_to")),
        instant(rs.getString("stated_at")), rs.getString("source_occurrence"), rs.getString("fact_group"), rs.getString("source_payload"))).list();
  }

  Optional<MemoryStore.FactHistory> factHistory(String profile, String memory,
                                                TemporalSnapshot snapshot) {
    String group = jdbc.sql("""
      SELECT a.fact_group FROM memory_assertions a
      JOIN assertion_revisions r ON r.assertion_id = a.id
      JOIN memories m ON m.id = r.memory_id
      WHERE a.profile_id = ? AND r.memory_id = ? AND m.forgotten = 0
      ORDER BY r.from_sequence LIMIT 1
      """).params(profile, memory).query(String.class).optional().orElse(null);
    if (group == null) return Optional.empty();

    List<RevisionResponse> revisions = jdbc.sql("""
      SELECT r.*, a.source_occurrence, a.fact_group, m.content
      FROM assertion_revisions r
      JOIN memory_assertions a ON a.id = r.assertion_id
      JOIN memories m ON m.id = r.memory_id
      WHERE a.profile_id = ? AND a.fact_group = ? AND m.forgotten = 0
      ORDER BY r.from_sequence, r.id
      """).params(profile, group).query((rs, _) -> new RevisionResponse(rs.getString("assertion_id"), rs.getString("id"),
        rs.getString("memory_id"), rs.getString("content"), instant(rs.getString("valid_from")), instant(rs.getString("valid_to")),
        instant(rs.getString("stated_at")), instant(rs.getString("recorded_from")), instant(rs.getString("recorded_to")),
        rs.getString("corrects"), rs.getString("source_occurrence"), rs.getInt("invalidated") != 0,
        rs.getString("fact_group"), rs.getString("source_payload"),
        rs.getString("conflict_evidence"))).list();

    List<TemporalValidity> segments = groupSegments(profile, group, null);
    List<TemporalValidity> selected = groupSegments(profile, group, snapshot);
    return Optional.of(new MemoryStore.FactHistory(group, revisions, segments, selected));
  }

  private List<TemporalValidity> groupSegments(String profile, String group,
                                               TemporalSnapshot snapshot) {
    String filter = snapshot == null ? "" : """
      AND s.valid_from <= ? AND (s.valid_to IS NULL OR ? < s.valid_to)
      AND s.recorded_from <= ? AND s.from_sequence <= ?
      AND (s.recorded_to IS NULL OR ? < s.recorded_to OR s.to_sequence > ?)
      """;
    var query = jdbc.sql("""
      SELECT a.id AS assertion_id, r.id AS revision_id, s.*, r.stated_at,
        a.source_occurrence, a.fact_group, r.source_payload
      FROM validity_segments s
      JOIN assertion_revisions r ON r.id = s.revision_id
      JOIN memory_assertions a ON a.id = r.assertion_id
      JOIN memories m ON m.id = r.memory_id
      WHERE a.profile_id = ? AND a.fact_group = ? AND m.forgotten = 0
      """ + filter + " ORDER BY s.from_sequence, s.valid_from, r.id");
    if (snapshot == null) {
      query = query.params(profile, group);
    } else {
      query = query.params(profile, group, timestamp(snapshot.asOf()), timestamp(snapshot.asOf()),
        timestamp(snapshot.knownAsOf()), snapshot.sequence(), timestamp(snapshot.knownAsOf()), snapshot.sequence());
    }
    return query.query((rs, _) -> new TemporalValidity(rs.getString("assertion_id"),
      rs.getString("revision_id"), instant(rs.getString("valid_from")), instant(rs.getString("valid_to")),
      instant(rs.getString("recorded_from")), instant(rs.getString("recorded_to")),
      instant(rs.getString("stated_at")),
      rs.getString("source_occurrence"), rs.getString("fact_group"), rs.getString("source_payload"))).list();
  }

  List<RevisionResponse> history(String profile, String memory) {
    return jdbc.sql("""
      SELECT r.*, a.source_occurrence, a.fact_group, m.content FROM assertion_revisions r
      JOIN memory_assertions a ON a.id = r.assertion_id JOIN memories m ON m.id = r.memory_id
      WHERE m.forgotten = 0 AND a.profile_id = ? AND a.id IN (SELECT assertion_id FROM assertion_revisions WHERE memory_id = ?)
      ORDER BY r.from_sequence, r.id
      """).params(profile, memory).query((rs, _) -> new RevisionResponse(rs.getString("assertion_id"), rs.getString("id"),
        rs.getString("memory_id"), rs.getString("content"), instant(rs.getString("valid_from")), instant(rs.getString("valid_to")),
        instant(rs.getString("stated_at")), instant(rs.getString("recorded_from")), instant(rs.getString("recorded_to")),
        rs.getString("corrects"), rs.getString("source_occurrence"), rs.getInt("invalidated") != 0, rs.getString("fact_group"), rs.getString("source_payload"), rs.getString("conflict_evidence"))).list();
  }
}
