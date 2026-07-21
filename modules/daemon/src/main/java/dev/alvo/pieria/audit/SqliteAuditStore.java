package dev.alvo.pieria.audit;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** SQLite implementation of the append-only audit store. */
@Repository
public class SqliteAuditStore implements AuditStore {

  private static final RowMapper<AuditEvent> MAPPER = (rs, _) -> map(rs);
  private final JdbcClient jdbc;

  public SqliteAuditStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void append(AuditEvent e) {
    jdbc.sql("""
        INSERT INTO profile_audit_events (
          id, profile_id, profile_name, event_type, operation, request_id, parent_request_id,
          task_id, session_id, resource_id, client, harness, channel, client_version,
          server_version, remote_address, method, path, query_string, request_media_type,
          response_media_type, started_at, completed_at, duration_ms, http_status, outcome,
          error_kind, error_message, metadata, request_body, request_bytes, request_sha256,
          request_truncated, response_body, response_bytes, response_sha256, response_truncated)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)
      .params(e.id(), e.profileId(), e.profileName(), e.eventType(), e.operation(), e.requestId(),
        e.parentRequestId(), e.taskId(), e.sessionId(), e.resourceId(), e.client(), e.harness(),
        e.channel(), e.clientVersion(), e.serverVersion(), e.remoteAddress(), e.method(), e.path(),
        e.queryString(), e.requestMediaType(), e.responseMediaType(), e.startedAt().toString(),
        e.completedAt().toString(), e.durationMs(), e.httpStatus(), e.outcome(), e.errorKind(),
        e.errorMessage(), value(e.metadata(), "{}"), value(e.requestBody(), ""), e.requestBytes(),
        e.requestSha256(), e.requestTruncated() ? 1 : 0, value(e.responseBody(), ""),
        e.responseBytes(), e.responseSha256(), e.responseTruncated() ? 1 : 0)
      .update();
  }

  @Override
  public List<AuditEvent> search(String profileName, AuditQuery q) {
    boolean fts = q.text() != null && !q.text().isBlank();
    StringBuilder sql = new StringBuilder("SELECT a.* FROM profile_audit_events a");
    List<Object> params = new ArrayList<>();
    if (fts) {
      sql.append(" JOIN profile_audit_fts f ON f.rowid = a.rowid");
    }
    sql.append(" WHERE a.profile_name = ?");
    params.add(profileName);
    if (fts) {
      String match = toFtsMatch(q.text());
      if (match == null) {
        return List.of();
      }
      sql.append(" AND profile_audit_fts MATCH ?");
      params.add(match);
    }
    add(sql, params, q.operation(), "a.operation = ?");
    add(sql, params, q.client(), "a.client = ?");
    add(sql, params, q.harness(), "a.harness = ?");
    add(sql, params, q.channel(), "a.channel = ?");
    add(sql, params, q.outcome(), "a.outcome = ?");
    add(sql, params, q.status(), "a.http_status = ?");
    add(sql, params, q.sessionId(), "a.session_id = ?");
    add(sql, params, q.taskId(), "a.task_id = ?");
    add(sql, params, q.requestId(), "a.request_id = ?");
    if (q.from() != null) {
      sql.append(" AND a.completed_at >= ?");
      params.add(q.from().toString());
    }
    if (q.to() != null) {
      sql.append(" AND a.completed_at <= ?");
      params.add(q.to().toString());
    }
    if (q.truncated() != null) {
      sql.append(q.truncated()
        ? " AND (a.request_truncated = 1 OR a.response_truncated = 1)"
        : " AND a.request_truncated = 0 AND a.response_truncated = 0");
    }
    if (q.cursorTime() != null && q.cursorId() != null) {
      sql.append(" AND (a.completed_at < ? OR (a.completed_at = ? AND a.id < ?))");
      params.add(q.cursorTime().toString());
      params.add(q.cursorTime().toString());
      params.add(q.cursorId());
    }
    sql.append(" ORDER BY a.completed_at DESC, a.id DESC LIMIT ?");
    params.add(q.limit());
    return jdbc.sql(sql.toString()).params(params).query(MAPPER).list();
  }

  @Override
  public Optional<AuditEvent> find(String profileName, String id) {
    return jdbc.sql("SELECT * FROM profile_audit_events WHERE profile_name = ? AND id = ?")
      .params(profileName, id).query(MAPPER).optional();
  }

  @Override
  @Transactional
  public void deleteForProfile(String profileId, String profileName) {
    jdbc.sql("DELETE FROM profile_audit_events WHERE profile_id = ? OR profile_name = ?")
      .params(profileId, profileName).update();
  }

  private static void add(StringBuilder sql, List<Object> params, Object value, String condition) {
    if (value != null && (!(value instanceof String s) || !s.isBlank())) {
      sql.append(" AND ").append(condition);
      params.add(value);
    }
  }

  private static String toFtsMatch(String query) {
    List<String> terms = new ArrayList<>();
    for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+")) {
      if (!token.isBlank()) {
        terms.add("\"" + token.replace("\"", "\"\"") + "\"*");
      }
    }
    return terms.isEmpty() ? null : String.join(" AND ", terms);
  }

  private static AuditEvent map(ResultSet rs) throws SQLException {
    Integer status = rs.getObject("http_status") == null ? null : rs.getInt("http_status");
    return new AuditEvent(
      rs.getString("id"), rs.getString("profile_id"), rs.getString("profile_name"),
      rs.getString("event_type"), rs.getString("operation"), rs.getString("request_id"),
      rs.getString("parent_request_id"), rs.getString("task_id"), rs.getString("session_id"),
      rs.getString("resource_id"), rs.getString("client"), rs.getString("harness"),
      rs.getString("channel"), rs.getString("client_version"), rs.getString("server_version"),
      rs.getString("remote_address"), rs.getString("method"), rs.getString("path"),
      rs.getString("query_string"), rs.getString("request_media_type"),
      rs.getString("response_media_type"), Instant.parse(rs.getString("started_at")),
      Instant.parse(rs.getString("completed_at")), rs.getLong("duration_ms"), status,
      rs.getString("outcome"), rs.getString("error_kind"), rs.getString("error_message"),
      rs.getString("metadata"), rs.getString("request_body"), rs.getLong("request_bytes"),
      rs.getString("request_sha256"), rs.getInt("request_truncated") != 0,
      rs.getString("response_body"), rs.getLong("response_bytes"),
      rs.getString("response_sha256"), rs.getInt("response_truncated") != 0);
  }

  private static String value(String value, String fallback) {
    return value == null ? fallback : value;
  }
}
