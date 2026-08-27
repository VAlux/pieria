package dev.alvo.pieria.storage;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A tiny fluent SELECT builder over {@link JdbcClient}: it removes the repeated string juggling
 * for dynamic {@code WHERE}/{@code ORDER BY} clauses without pulling in a query DSL or ORM.
 *
 * <p>Deliberately reflection-free — callers pass explicit {@link RowMapper}s — so it stays
 * GraalVM native-image friendly. SELECT only; writes and SQLite-specific statements
 * (FTS5/vec/{@code INSERT OR IGNORE}) are issued directly through {@link JdbcClient}.
 */
final class SqlSelect {

  private final JdbcClient jdbc;
  private final StringBuilder sql;
  private final List<Object> params = new ArrayList<>();
  private boolean whereStarted = false;

  private SqlSelect(JdbcClient jdbc, String columns, String table) {
    this.jdbc = jdbc;
    this.sql = new StringBuilder("SELECT ").append(columns).append(" FROM ").append(table);
  }

  static SqlSelect from(JdbcClient jdbc, String columns, String table) {
    return new SqlSelect(jdbc, columns, table);
  }

  /**
   * Add a condition, joined with {@code AND} if a previous condition exists.
   */
  SqlSelect where(String condition, Object... args) {
    sql.append(whereStarted ? " AND " : " WHERE ").append(condition);
    whereStarted = true;
    for (Object arg : args) {
      params.add(arg);
    }
    return this;
  }

  /**
   * Alias for {@link #where} that reads naturally after the first condition.
   */
  SqlSelect and(String condition, Object... args) {
    return where(condition, args);
  }

  /**
   * Add a condition only when {@code include} is true (args are ignored otherwise).
   */
  SqlSelect andIf(boolean include, String condition, Object... args) {
    return include ? where(condition, args) : this;
  }

  SqlSelect orderBy(String orderClause) {
    sql.append(" ORDER BY ").append(orderClause);
    return this;
  }

  SqlSelect limit(int max) {
    sql.append(" LIMIT ").append(max);
    return this;
  }

  <T> List<T> map(RowMapper<T> mapper) {
    return jdbc.sql(sql.toString()).params(params).query(mapper).list();
  }

  <T> Optional<T> findOne(RowMapper<T> mapper) {
    return jdbc.sql(sql.toString()).params(params).query(mapper).optional();
  }
}
