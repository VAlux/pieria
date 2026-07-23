package dev.alvo.pieria.storage;

import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.domain.code.CodeEdge;
import dev.alvo.pieria.domain.code.CodeFile;
import dev.alvo.pieria.domain.code.CodeModule;
import dev.alvo.pieria.domain.code.CodeRelation;
import dev.alvo.pieria.domain.code.CodeSymbol;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded SQLite backend for {@link CodeIndexStore}, hand-written SQL against the V5 schema via
 * {@link JdbcClient}. Shares the datasource with {@link SqliteMemoryStore} so an orchestrating
 * {@code @Transactional} service commits code-index writes, derived memories, and the graph
 * projection together.
 */
@Repository
public class SqliteCodeIndexStore implements CodeIndexStore {

  private static final String SYMBOL_COLUMNS =
    "id, profile_id, file_id, kind, name, qualified_name, signature, visibility, "
      + "start_line, end_line, language, parent_symbol_id, path";

  /** Identifier-ish tokens for FTS; anything else is dropped so user text can't break FTS5 syntax. */
  private static final Pattern FTS_TOKEN = Pattern.compile("[\\p{Alnum}_]+");

  private final JdbcClient jdbc;

  public SqliteCodeIndexStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static CodeSymbol mapSymbol(ResultSet rs) throws SQLException {
    return mapSymbol(rs, "");
  }

  /** Map a symbol whose columns were selected under {@code prefix}-ed aliases (e.g. {@code s_id}). */
  private static CodeSymbol mapSymbol(ResultSet rs, String prefix) throws SQLException {
    return new CodeSymbol(
      rs.getString(prefix + "id"),
      rs.getString(prefix + "profile_id"),
      rs.getString(prefix + "file_id"),
      CodeSymbolKind.fromWire(rs.getString(prefix + "kind")),
      rs.getString(prefix + "name"),
      rs.getString(prefix + "qualified_name"),
      rs.getString(prefix + "signature"),
      rs.getString(prefix + "visibility"),
      rs.getInt(prefix + "start_line"),
      rs.getInt(prefix + "end_line"),
      rs.getString(prefix + "language"),
      rs.getString(prefix + "parent_symbol_id"),
      rs.getString(prefix + "path"));
  }

  /** Build a safe FTS5 MATCH string: identifier tokens, each quoted, OR-joined. Empty ⇒ no match. */
  private static String toFtsMatch(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    Matcher m = FTS_TOKEN.matcher(raw);
    List<String> terms = new ArrayList<>();
    while (m.find()) {
      terms.add("\"" + m.group() + "\"");
    }
    return String.join(" OR ", terms);
  }

  private static String placeholders(int n) {
    return String.join(", ", java.util.Collections.nCopies(n, "?"));
  }

  @Override
  @Transactional
  public CodeModule upsertCodeModule(String profileId, CodeModule module) {
    String id = module.id() != null ? module.id() : ContentId.forCodeModule(profileId, module.path());
    Instant createdAt = module.createdAt() == null ? Instant.now() : module.createdAt();
    jdbc.sql("""
        INSERT OR IGNORE INTO code_modules (id, profile_id, name, path, created_at) \
        VALUES (?, ?, ?, ?, ?)""")
      .params(id, profileId, module.name(), module.path(), createdAt.toString())
      .update();
    return new CodeModule(id, profileId, module.name(), module.path(), createdAt);
  }

  @Override
  @Transactional
  public CodeFile upsertCodeFile(String profileId, CodeFile file) {
    String id = file.id() != null ? file.id() : ContentId.forCodeFile(profileId, file.repoRelPath());
    Instant indexedAt = file.indexedAt() == null ? Instant.now() : file.indexedAt();
    jdbc.sql("""
        INSERT INTO code_files \
          (id, profile_id, language, repo_rel_path, content_hash, loc, module_id, indexed_at) \
        VALUES (?, ?, ?, ?, ?, ?, ?, ?) \
        ON CONFLICT(id) DO UPDATE SET \
          language = excluded.language, content_hash = excluded.content_hash, \
          loc = excluded.loc, module_id = excluded.module_id, indexed_at = excluded.indexed_at""")
      .params(id, profileId, file.language(), file.repoRelPath(), file.contentHash(),
        file.loc(), file.moduleId(), indexedAt.toString())
      .update();
    return new CodeFile(id, profileId, file.language(), file.repoRelPath(), file.contentHash(),
      file.loc(), file.moduleId(), indexedAt);
  }

  @Override
  @Transactional
  public CodeSymbol upsertCodeSymbol(String profileId, CodeSymbol symbol) {
    String id = symbol.id() != null
      ? symbol.id()
      : ContentId.forCodeSymbol(profileId, symbol.fileId(), symbol.kind().wire(),
          symbol.qualifiedName(), symbol.signature());
    jdbc.sql("""
        INSERT OR IGNORE INTO code_symbols \
          (id, profile_id, file_id, kind, name, qualified_name, signature, visibility, \
           start_line, end_line, language, parent_symbol_id, path) \
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
      .params(id, profileId, symbol.fileId(), symbol.kind().wire(), symbol.name(),
        symbol.qualifiedName(), symbol.signature(), symbol.visibility(),
        symbol.startLine(), symbol.endLine(), symbol.language(), symbol.parentSymbolId(), symbol.path())
      .update();
    return new CodeSymbol(id, profileId, symbol.fileId(), symbol.kind(), symbol.name(),
      symbol.qualifiedName(), symbol.signature(), symbol.visibility(), symbol.startLine(),
      symbol.endLine(), symbol.language(), symbol.parentSymbolId(), symbol.path());
  }

  @Override
  @Transactional
  public CodeEdge upsertCodeEdge(String profileId, CodeEdge edge) {
    String id = edge.id() != null
      ? edge.id()
      : ContentId.forCodeEdge(profileId, edge.srcSymbolId(), edge.relation().wire(),
          edge.dstRef(), edge.confidence().wire());
    jdbc.sql("""
        INSERT OR IGNORE INTO code_edges \
          (id, profile_id, src_symbol_id, relation, confidence, dst_symbol_id, dst_ref, file_id) \
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
      .params(id, profileId, edge.srcSymbolId(), edge.relation().wire(), edge.confidence().wire(),
        edge.dstSymbolId(), edge.dstRef(), edge.fileId())
      .update();
    return new CodeEdge(id, profileId, edge.srcSymbolId(), edge.relation(), edge.confidence(),
      edge.dstSymbolId(), edge.dstRef(), edge.fileId());
  }

  @Override
  public Optional<String> fileContentHash(String profileId, String repoRelPath) {
    return jdbc.sql("SELECT content_hash FROM code_files WHERE profile_id = ? AND repo_rel_path = ?")
      .params(profileId, repoRelPath)
      .query(String.class)
      .optional();
  }

  @Override
  public boolean hasRecallableFileStructure(String profileId, String repoRelPath) {
    return jdbc.sql("""
        SELECT EXISTS(
          SELECT 1 FROM code_symbols s
          JOIN code_files f ON f.id = s.file_id
          WHERE f.profile_id = ? AND f.repo_rel_path = ?
          UNION ALL
          SELECT 1 FROM code_edges e
          JOIN code_files f ON f.id = e.file_id
          WHERE f.profile_id = ? AND f.repo_rel_path = ?
            AND e.relation IN ('depends-on', 'tests', 'handles-route')
        )""")
      .params(profileId, repoRelPath, profileId, repoRelPath)
      .query(Integer.class)
      .single() == 1;
  }

  @Override
  @Transactional
  public void replaceFileIndex(String profileId, CodeFile file, List<CodeSymbol> symbols, List<CodeEdge> edges) {
    CodeFile stored = upsertCodeFile(profileId, file);
    String fileId = stored.id();

    // Edges first (they reference symbols), then symbols.
    jdbc.sql("DELETE FROM code_edges WHERE profile_id = ? AND file_id = ?").params(profileId, fileId).update();
    jdbc.sql("DELETE FROM code_symbols WHERE profile_id = ? AND file_id = ?").params(profileId, fileId).update();

    for (CodeSymbol s : symbols == null ? List.<CodeSymbol>of() : symbols) {
      upsertCodeSymbol(profileId, withFile(s, fileId, stored.repoRelPath()));
    }
    for (CodeEdge e : edges == null ? List.<CodeEdge>of() : edges) {
      upsertCodeEdge(profileId, withFile(e, fileId));
    }
  }

  private static CodeSymbol withFile(CodeSymbol s, String fileId, String path) {
    return new CodeSymbol(s.id(), s.profileId(), s.fileId() == null ? fileId : s.fileId(), s.kind(),
      s.name(), s.qualifiedName(), s.signature(), s.visibility(), s.startLine(), s.endLine(),
      s.language(), s.parentSymbolId(), s.path() == null ? path : s.path());
  }

  private static CodeEdge withFile(CodeEdge e, String fileId) {
    return new CodeEdge(e.id(), e.profileId(), e.srcSymbolId(), e.relation(), e.confidence(),
      e.dstSymbolId(), e.dstRef(), e.fileId() == null ? fileId : e.fileId());
  }

  @Override
  public List<CodeSymbol> searchSymbolsFts(String profileId, String matchQuery, int limit) {
    String match = toFtsMatch(matchQuery);
    if (match.isEmpty() || limit <= 0) {
      return List.of();
    }
    return jdbc.sql("SELECT " + qualified("s") + " FROM code_symbols_fts f "
        + "JOIN code_symbols s ON s.rowid = f.rowid "
        + "WHERE f.code_symbols_fts MATCH ? AND s.profile_id = ? "
        + "ORDER BY f.rank LIMIT ?")
      .params(match, profileId, limit)
      .query((rs, _) -> mapSymbol(rs))
      .list();
  }

  @Override
  public List<CodeSymbol> findSymbolsByName(String profileId, List<String> names, int limit) {
    return findSymbolsByColumn("name", profileId, names, limit);
  }

  @Override
  public List<CodeSymbol> findSymbolsByQualifiedName(String profileId, List<String> qualifiedNames, int limit) {
    return findSymbolsByColumn("qualified_name", profileId, qualifiedNames, limit);
  }

  private List<CodeSymbol> findSymbolsByColumn(String column, String profileId, List<String> values, int limit) {
    if (values == null || values.isEmpty() || limit <= 0) {
      return List.of();
    }
    List<String> distinct = values.stream().filter(v -> v != null && !v.isBlank()).distinct().toList();
    if (distinct.isEmpty()) {
      return List.of();
    }
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.addAll(distinct);
    params.add(limit);
    return jdbc.sql("SELECT " + SYMBOL_COLUMNS + " FROM code_symbols "
        + "WHERE profile_id = ? AND " + column + " IN (" + placeholders(distinct.size()) + ") "
        + "ORDER BY qualified_name LIMIT ?")
      .params(params)
      .query((rs, _) -> mapSymbol(rs))
      .list();
  }

  @Override
  public List<CodeSymbol> findSymbolsByIds(String profileId, List<String> ids, int limit) {
    if (ids == null || ids.isEmpty() || limit <= 0) {
      return List.of();
    }
    List<String> distinct = ids.stream().filter(i -> i != null && !i.isBlank()).distinct().toList();
    if (distinct.isEmpty()) {
      return List.of();
    }
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.addAll(distinct);
    List<CodeSymbol> found = jdbc.sql("SELECT " + SYMBOL_COLUMNS + " FROM code_symbols "
        + "WHERE profile_id = ? AND id IN (" + placeholders(distinct.size()) + ")")
      .params(params)
      .query((rs, _) -> mapSymbol(rs))
      .list();
    // Preserve the requested id order.
    found.sort(Comparator.comparingInt(s -> distinct.indexOf(s.id())));
    return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
  }

  @Override
  public List<String> symbolNeighborhood(
    String profileId, List<String> seedSymbolIds, int depth, int fanout, EdgeConfidence minConfidence) {
    if (seedSymbolIds == null || seedSymbolIds.isEmpty()) {
      return List.of();
    }
    List<String> allowed = allowedConfidences(minConfidence);
    LinkedHashSet<String> visited = new LinkedHashSet<>();
    List<String> frontier = new ArrayList<>();
    for (String s : seedSymbolIds) {
      if (s != null && !s.isBlank() && visited.add(s)) {
        frontier.add(s);
      }
    }
    int hops = Math.max(0, depth);
    for (int d = 0; d < hops && !frontier.isEmpty(); d++) {
      List<String> next = neighbors(profileId, frontier, fanout, allowed);
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

  /** One hop over resolvable code edges (both directions), bounded by fanout, deterministic order. */
  private List<String> neighbors(String profileId, List<String> frontier, int fanout, List<String> allowed) {
    if (frontier.isEmpty() || fanout <= 0 || allowed.isEmpty()) {
      return List.of();
    }
    String frontierPh = placeholders(frontier.size());
    String confPh = placeholders(allowed.size());
    List<Object> params = new ArrayList<>();
    // outgoing: src in frontier → dst
    params.add(profileId);
    params.addAll(frontier);
    params.addAll(allowed);
    // incoming: dst in frontier → src
    params.add(profileId);
    params.addAll(frontier);
    params.addAll(allowed);
    params.add(fanout);
    List<String> rows = jdbc.sql("SELECT neighbor FROM ( "
        + "  SELECT dst_symbol_id AS neighbor FROM code_edges "
        + "    WHERE profile_id = ? AND src_symbol_id IN (" + frontierPh + ") "
        + "      AND dst_symbol_id IS NOT NULL AND confidence IN (" + confPh + ") "
        + "  UNION "
        + "  SELECT src_symbol_id AS neighbor FROM code_edges "
        + "    WHERE profile_id = ? AND dst_symbol_id IN (" + frontierPh + ") "
        + "      AND confidence IN (" + confPh + ") "
        + ") ORDER BY neighbor ASC LIMIT ?")
      .params(params)
      .query(String.class)
      .list();
    return List.copyOf(new LinkedHashSet<>(rows));
  }

  @Override
  public List<EdgeEvidence> findEdgesTouching(
    String profileId, List<String> symbolIds, EdgeConfidence minConfidence, int limit) {
    if (symbolIds == null || limit <= 0) {
      return List.of();
    }
    List<String> ids = symbolIds.stream().filter(i -> i != null && !i.isBlank()).distinct().toList();
    List<String> allowed = allowedConfidences(minConfidence);
    if (ids.isEmpty() || allowed.isEmpty()) {
      return List.of();
    }
    String idPh = placeholders(ids.size());
    String confPh = placeholders(allowed.size());
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.addAll(ids);
    params.addAll(ids);
    params.addAll(allowed);
    params.add(limit);
    return jdbc.sql("SELECT "
        + "e.id AS e_id, e.profile_id AS e_profile_id, e.src_symbol_id AS e_src_symbol_id, "
        + "e.relation AS e_relation, e.confidence AS e_confidence, "
        + "e.dst_symbol_id AS e_dst_symbol_id, e.dst_ref AS e_dst_ref, e.file_id AS e_file_id, "
        + prefixed("s") + ", " + prefixed("d") + " "
        + "FROM code_edges e "
        + "JOIN code_symbols s ON s.id = e.src_symbol_id "
        + "LEFT JOIN code_symbols d ON d.id = e.dst_symbol_id "
        + "WHERE e.profile_id = ? "
        + "  AND (e.src_symbol_id IN (" + idPh + ") OR e.dst_symbol_id IN (" + idPh + ")) "
        + "  AND e.confidence IN (" + confPh + ") "
        + "ORDER BY CASE e.confidence WHEN '" + EdgeConfidence.RESOLVED.wire() + "' THEN 0 ELSE 1 END, "
        + "  e.relation ASC, s.qualified_name ASC, e.id ASC "
        + "LIMIT ?")
      .params(params)
      .query((rs, _) -> mapEdgeEvidence(rs))
      .list();
  }

  private static EdgeEvidence mapEdgeEvidence(ResultSet rs) throws SQLException {
    CodeEdge edge = new CodeEdge(
      rs.getString("e_id"),
      rs.getString("e_profile_id"),
      rs.getString("e_src_symbol_id"),
      CodeRelation.fromWire(rs.getString("e_relation")),
      EdgeConfidence.fromWire(rs.getString("e_confidence")),
      rs.getString("e_dst_symbol_id"),
      rs.getString("e_dst_ref"),
      rs.getString("e_file_id"));
    CodeSymbol src = mapSymbol(rs, "s_");
    CodeSymbol dst = rs.getString("d_id") == null ? null : mapSymbol(rs, "d_");
    return new EdgeEvidence(edge, src, dst);
  }

  /** Symbol columns of {@code alias} selected under {@code alias_}-prefixed names. */
  private static String prefixed(String alias) {
    StringBuilder sb = new StringBuilder();
    for (String col : SYMBOL_COLUMNS.split(", ")) {
      if (!sb.isEmpty()) {
        sb.append(", ");
      }
      sb.append(alias).append('.').append(col).append(" AS ").append(alias).append('_').append(col);
    }
    return sb.toString();
  }

  private static List<String> allowedConfidences(EdgeConfidence minConfidence) {
    EdgeConfidence min = minConfidence == null ? EdgeConfidence.HEURISTIC : minConfidence;
    List<String> allowed = new ArrayList<>();
    for (EdgeConfidence c : EdgeConfidence.values()) {
      if (c.rank() >= min.rank()) {
        allowed.add(c.wire());
      }
    }
    return allowed;
  }

  @Override
  public boolean isCodeIndexPresent(String profileId) {
    Long n = jdbc.sql("SELECT COUNT(*) FROM code_files WHERE profile_id = ?")
      .param(profileId)
      .query(Long.class)
      .single();
    return n != null && n > 0;
  }

  @Override
  public CodeIndexCounts counts(String profileId) {
    long files = count("SELECT COUNT(*) FROM code_files WHERE profile_id = ?", profileId);
    long symbols = count("SELECT COUNT(*) FROM code_symbols WHERE profile_id = ?", profileId);
    long resolved = count(
      "SELECT COUNT(*) FROM code_edges WHERE profile_id = ? AND confidence = '" + EdgeConfidence.RESOLVED.wire() + "'",
      profileId);
    long heuristic = count(
      "SELECT COUNT(*) FROM code_edges WHERE profile_id = ? AND confidence = '" + EdgeConfidence.HEURISTIC.wire() + "'",
      profileId);
    return new CodeIndexCounts(files, symbols, resolved, heuristic);
  }

  private long count(String sql, String profileId) {
    Long n = jdbc.sql(sql).param(profileId).query(Long.class).single();
    return n == null ? 0 : n;
  }

  private static String qualified(String alias) {
    StringBuilder sb = new StringBuilder();
    for (String col : SYMBOL_COLUMNS.split(", ")) {
      if (!sb.isEmpty()) {
        sb.append(", ");
      }
      sb.append(alias).append('.').append(col);
    }
    return sb.toString();
  }
}
