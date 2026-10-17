package dev.alvo.pieria.storage;

import dev.alvo.pieria.api.request.RevisionRequest;
import dev.alvo.pieria.api.response.RevisionResponse;
import dev.alvo.pieria.api.response.TemporalValidity;
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
import dev.alvo.pieria.domain.memory.MemoryTimes;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.domain.profile.ProfileCount;
import dev.alvo.pieria.domain.profile.ProfileStats;
import dev.alvo.pieria.domain.profile.ProfileUsage;
import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
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
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Embedded SQLite backend for {@link MemoryStore}. Hand-written SQL against the V1 schema
 * via Spring's {@link JdbcClient}. Writes are content-addressed and idempotent via
 * {@code INSERT OR IGNORE}; forgetting is independent from temporal resolution.
 */
@Repository
public class SqliteMemoryStore implements MemoryStore {

  @Override
  public <T> T readClusters(java.util.function.Function<dev.alvo.pieria.cluster.ClusterRepository,T> work) {
    return work.apply(new dev.alvo.pieria.cluster.SqliteClusterStore(jdbc,this));
  }

  @Override
  @Transactional
  public <T> T writeClusters(java.util.function.Function<dev.alvo.pieria.cluster.ClusterRepository,T> work) {
    jdbc.sql("UPDATE cluster_lock SET id=id WHERE id=1").update();
    return work.apply(new dev.alvo.pieria.cluster.SqliteClusterStore(jdbc,this));
  }

  @Override
  public boolean eligibleClusterMemory(String profileId,String memoryId,TemporalSnapshot snapshot,
                                       boolean excludeCodeDerived) {
    return new dev.alvo.pieria.cluster.SqliteClusterStore(jdbc,this)
      .eligibleMemory(profileId,memoryId,snapshot,excludeCodeDerived);
  }

  @Override
  public Set<String> clusterCoveredOriginals(String profileId,String memoryId,String query,
                                             TemporalSnapshot snapshot) {
    return new dev.alvo.pieria.cluster.SqliteClusterStore(jdbc,this)
      .coveredOriginals(profileId,memoryId,query,snapshot);
  }

  @Override
  public List<Memory> searchClusterClaims(String profileId,String query,float[] queryEmbedding,TemporalSnapshot snapshot,
                                           boolean excludeCodeDerived,int limit) {
    if(limit<1) return List.of();
    Set<String> ids=new LinkedHashSet<>();
    String match=query==null?"":java.util.Arrays.stream(query.toLowerCase(java.util.Locale.ROOT)
        .split("[^\\p{L}\\p{N}]+"))
      .filter(term->term.length()>2).distinct().limit(10)
      .map(term->"\""+term+"\"").collect(java.util.stream.Collectors.joining(" OR "));
    if(!match.isBlank()) ids.addAll(jdbc.sql("""
      SELECT s.memory_id FROM cluster_claims_fts
      JOIN cluster_claims claim ON claim.rowid=cluster_claims_fts.rowid
      JOIN cluster_syntheses s ON s.id=claim.synthesis_id
      JOIN clusters c ON c.id=s.cluster_id
      WHERE cluster_claims_fts MATCH ? AND c.profile_id=?
      GROUP BY s.memory_id ORDER BY MAX(s.recorded_sequence) DESC,s.memory_id
      LIMIT ?
      """).params(match,profileId,Math.min(300,limit*3)).query(String.class).list());
    if(queryEmbedding!=null && isVectorSearchAvailable()) {
      try {
        record ClaimVector(String memoryId,byte[] embedding) {}
        for(ClaimVector hit:jdbc.sql("""
          SELECT s.memory_id,claim.embedding FROM cluster_claims claim
          JOIN cluster_syntheses s ON s.id=claim.synthesis_id
          JOIN clusters c ON c.id=s.cluster_id
          WHERE c.profile_id=? AND claim.embedding IS NOT NULL
          ORDER BY vec_distance_L2(claim.embedding,?),s.recorded_sequence DESC LIMIT ?
          """).params(profileId,encodeEmbedding(queryEmbedding),Math.min(300,limit*3))
          .query((rs,_) -> new ClaimVector(rs.getString(1),rs.getBytes(2))).list()) {
          float[] vector=decodeEmbedding(hit.embedding());
          if(vector.length==queryEmbedding.length &&
              dev.alvo.pieria.tools.Vectors.cosine(queryEmbedding,vector)>=.78)
            ids.add(hit.memoryId());
        }
      } catch(RuntimeException e) {
        // A missing vector extension or changed embedding width cannot break lexical recall.
      }
    }
    List<Memory> result=new ArrayList<>();
    for(String id:ids) {
      if(!eligibleClusterMemory(profileId,id,snapshot,excludeCodeDerived)) continue;
      atSnapshot(snapshot,()->findMemoryById(id)).ifPresent(result::add);
      if(result.size()>=limit) break;
    }
    return result;
  }
  @Override
  public List<Memory> relevantClusterParents(String profileId,List<String> seedMemoryIds,String query,
                                              TemporalSnapshot snapshot,boolean excludeCodeDerived,int limit) {
    List<Memory> result=new ArrayList<>();
    for(String id:new dev.alvo.pieria.cluster.SqliteClusterStore(jdbc,this)
        .relevantParents(profileId,seedMemoryIds,query,snapshot,limit)) {
      if(!eligibleClusterMemory(profileId,id,snapshot,excludeCodeDerived)) continue;
      atSnapshot(snapshot,()->findMemoryById(id)).ifPresent(result::add);
    }
    return result;
  }

  @Override
  public <T> T readWiki(java.util.function.Function<dev.alvo.pieria.wiki.WikiRepository,T> work) {
    // The embedded data source uses BEGIN IMMEDIATE for Spring transactions. A read-only
    // transaction would still reserve SQLite's sole writer for the entire wiki response.
    // WikiRepository captures a temporal watermark and its queries are independently scoped.
    return work.apply(new SqliteWikiStore(jdbc, this));
  }

  @Override
  @Transactional
  public <T> T writeWiki(java.util.function.Function<dev.alvo.pieria.wiki.WikiRepository,T> work) {
    // Reserve the single writer before reading, avoiding a WAL read-to-write upgrade race.
    jdbc.sql("UPDATE wiki_lock SET id=id WHERE id=1").update();
    return work.apply(new SqliteWikiStore(jdbc, this));
  }

  private static final Logger log = LoggerFactory.getLogger(SqliteMemoryStore.class);

  /**
   * Topic-key namespace owned by the code indexer; exempt from near-duplicate supersession.
   */
  private static final String CODE_TOPIC_NAMESPACE = "code:";

  /**
   * Topic-key namespace owned by execution-trace outcome events
   * ({@code dev.alvo.pieria.ingestion.trace.TraceMemoryFactory.OUTCOME_KEY_PREFIX}). EVENT is
   * otherwise append-only (see {@link #store}); a trace outcome is the one EVENT population keyed
   * so that the latest run of a command supersedes the previous one instead of accumulating one row
   * per run (execution-trace-memory design D5).
   */
  private static final String TRACE_OUTCOME_TOPIC_NAMESPACE = "trace:outcome:";

  private static final double DEFAULT_NEAR_DUPLICATE_THRESHOLD =
    Double.parseDouble(PieriaProperties.NEAR_DUPLICATE_THRESHOLD_DEFAULT);

  /**
   * How many FTS candidates to score for near-duplicate supersession. A restatement shares nearly
   * all its content words, so it ranks at the very top; measured against real profiles, the true
   * duplicate was inside the top 10 every time it existed.
   */
  private static final int NEAR_DUPLICATE_CANDIDATES = 10;
  private static final String MEMORY_COLUMNS =
    "id, session_id, type, content, topic_key, supersedes, superseded, payload, embed_text, created_at";
  private static final String ENTITY_COLUMNS = "id, profile_id, type, name, payload, created_at";
  /**
   * Edge columns qualified with the {@code e} alias — {@code edges} and {@code memories} share
   * {@code id}, {@code profile_id} and {@code created_at}, so any query joining both must qualify.
   */
  private static final String EDGE_COLUMNS =
    "e.id, e.profile_id, e.source_entity_id, e.target_entity_id, e.relation, e.memory_id, e.created_at";

  /**
   * The ids of every entity an active edge touches, as a subquery. Binds profileId twice.
   */
  private static String connectedEntityIds() {
    return "SELECT e.source_entity_id AS eid FROM edges e JOIN memories m ON m.id = e.memory_id   WHERE e.profile_id = ? AND " + known("e") + " AND " + active("m") + " UNION SELECT e.target_entity_id FROM edges e JOIN memories m ON m.id = e.memory_id   WHERE e.profile_id = ? AND " + known("e") + " AND " + active("m") + "";
  }

  private static final ScopedValue<TemporalSnapshot> SNAPSHOT = ScopedValue.newInstance();

  private static String active(String alias) {
    var snapshot = SNAPSHOT.isBound() ? SNAPSHOT.get() :
      new TemporalSnapshot(Instant.now(), Instant.now(), Long.MAX_VALUE, false);
    return SqliteTemporalStore.visible(alias, snapshot);
  }

  private static String activeEntity(String alias) {
    return "EXISTS (SELECT 1 FROM edges e JOIN memories m ON m.id = e.memory_id WHERE e.profile_id = "
      + alias + ".profile_id AND (e.source_entity_id = " + alias + ".id OR e.target_entity_id = " + alias + ".id) AND "
      + known("e") + " AND " + active("m") + ")";
  }

  private static String known(String alias) {
    Instant at = SNAPSHOT.isBound() ? SNAPSHOT.get().knownAsOf() : Instant.now();
    return alias + ".recorded_at <= '" + TemporalSnapshot.timestamp(at) + "' AND " + alias + ".recorded_sequence <= " + (SNAPSHOT.isBound() ? SNAPSHOT.get().sequence() : Long.MAX_VALUE);
  }

  @Override
  public List<TemporalValidity> validityHistory(String profileId, String memoryId) {
    return new SqliteTemporalStore(jdbc).segments(profileId, memoryId);
  }

  @Override
  public boolean supportsTemporalHistory() {
    return true;
  }

  @Override
  public TemporalSnapshot temporalSnapshot(String asOf, String knownAsOf) {
    return new SqliteTemporalStore(jdbc).snapshot(asOf, knownAsOf);
  }

  @Override
  public <T> T atSnapshot(TemporalSnapshot snapshot, Supplier<T> action) {
    return ScopedValue.where(SNAPSHOT, snapshot).call(action::get);
  }

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
  @Override
  @Transactional
  public RevisionResponse revise(String profileId, String memoryId,
                                 RevisionRequest request) {
    jdbc.sql("UPDATE temporal_transactions SET sequence = sequence WHERE sequence = (SELECT MAX(sequence) FROM temporal_transactions)").update();
    var temporal = new SqliteTemporalStore(jdbc);
    var current = temporal.history(profileId, memoryId).stream()
      .filter(r -> r.assertionId().equals(request.assertionId()) && r.recordedTo() == null)
      .findFirst().orElseThrow(TemporalConflictException::new);
    if (!current.revisionId().equals(request.expectedRevisionId()) || isForgotten(current.memoryId()))
      throw new TemporalConflictException();
    Instant from = request.validFrom() == null ? current.validFrom() : TemporalSnapshot.parse(request.validFrom());
    Instant to = request.validTo() == null ? current.validTo() : TemporalSnapshot.parse(request.validTo());
    boolean invalidated = false;
    if (Boolean.TRUE.equals(request.openEnded())) {
      if (request.validTo() != null || request.invalidateAt() != null)
        throw new IllegalArgumentException("openEnded cannot be combined with validTo or invalidateAt");
      to = null;
    }
    if (request.invalidateAt() != null) {
      if (request.content() != null || request.validFrom() != null || request.validTo() != null)
        throw new IllegalArgumentException("invalidateAt cannot be combined with correction fields");
      Instant cutoff = TemporalSnapshot.parse(request.invalidateAt());
      invalidated = !cutoff.isAfter(from);
      if (!invalidated && (to == null || cutoff.isBefore(to))) to = cutoff;
    }
    if (to != null && !from.isBefore(to)) throw new IllegalArgumentException("validFrom must precede validTo");
    if (request.content() != null && request.content().isBlank())
      throw new IllegalArgumentException("content must not be blank");
    var tx = temporal.transaction();
    int closed = jdbc.sql("UPDATE assertion_revisions SET recorded_to = ?, to_sequence = ? WHERE id = ? AND to_sequence IS NULL")
      .params(TemporalSnapshot.timestamp(tx.at()), tx.sequence(), current.revisionId()).update();
    if (closed != 1) throw new TemporalConflictException();
    Memory original = memoryByIdAndProfile(current.memoryId(), profileId).orElseThrow();
    Memory corrected = original;
    if (request.content() != null && !request.content().equals(original.content())) {
      corrected = insertMemoryRecord(profileId, Memory.of(original.type(), request.content(), original.sessionId(),
        original.topicKey(), original.payload()), 0).stored();
      if (isForgotten(corrected.id())) throw new IllegalArgumentException("Cannot revise to forgotten content");
      if (corrected.type() != MemoryType.TASK)
        jdbc.sql("INSERT OR IGNORE INTO vectorization_outbox(memory_id, enqueued_at, attempts) VALUES (?, ?, 0)")
          .params(corrected.id(), tx.at().toString()).update();
    }
    String revision = UUID.randomUUID().toString();
    temporal.insertRevision(revision, current.assertionId(), corrected.id(), from, to, current.statedAt(), tx, current.revisionId(), invalidated);
    String group = jdbc.sql("SELECT fact_group FROM memory_assertions WHERE id = ?").param(current.assertionId()).query(String.class).single();
    jdbc.sql("UPDATE assertion_revisions SET source_payload = ?, conflict_evidence = ? WHERE id = ?")
      .params(current.sourcePayload(), new ObjectMapper().writeValueAsString(request), revision).update();
    temporal.resolve(profileId, group, tx);
    return temporal.history(profileId, corrected.id()).stream().filter(r -> r.revisionId().equals(revision)).findFirst().orElseThrow();
  }

  @Override
  public List<RevisionResponse> conflictCandidates(String profileId, Memory incoming,
                                                   GraphFragment graph, int limit) {
    int channelLimit = Math.max(1, limit / 3);
    Set<String> ids = new LinkedHashSet<>();
    if (incoming.topicKey() != null) {
      ids.addAll(jdbc.sql("SELECT id FROM memories WHERE profile_id = ? AND type = ? AND topic_key = ? AND forgotten = 0 ORDER BY created_at DESC LIMIT ?")
        .params(profileId, incoming.type().wire(), incoming.topicKey(), channelLimit).query(String.class).list());
    }
    String match = toFtsMatch(incoming.content());
    if (match != null) ids.addAll(jdbc.sql("SELECT m.id FROM memories_fts f JOIN memories m ON m.rowid = f.rowid "
        + "WHERE memories_fts MATCH ? AND m.profile_id = ? AND m.type = ? AND m.forgotten = 0 "
        + "AND (m.topic_key IS NULL OR m.topic_key NOT LIKE 'code:%') ORDER BY f.rank LIMIT ?")
      .params(match, profileId, incoming.type().wire(), channelLimit).query(String.class).list());
    if (graph != null && !graph.entities().isEmpty()) {
      var names = graph.entities().stream().map(Entity::name).distinct().limit(channelLimit).toList();
      var params = new ArrayList<Object>();
      params.add(profileId);
      params.add(incoming.type().wire());
      params.addAll(names);
      params.add(channelLimit);
      ids.addAll(jdbc.sql("SELECT DISTINCT m.id FROM entities en JOIN edges e ON en.id IN (e.source_entity_id, e.target_entity_id) "
          + "JOIN memories m ON m.id = e.memory_id WHERE m.profile_id = ? AND m.type = ? AND m.forgotten = 0 "
          + "AND en.name IN (" + String.join(",", Collections.nCopies(names.size(), "?")) + ") LIMIT ?")
        .params(params).query(String.class).list());
    }
    // Reuse an already available content embedding; conflict checking never embeds inside a transaction.
    String existingId = resolveMemoryId(profileId, incoming);
    var embedding = embeddingsFor(profileId, List.of(existingId)).get(existingId);
    if (embedding != null && isVectorSearchAvailable()) {
      ids.addAll(jdbc.sql("SELECT id FROM memories WHERE profile_id = ? AND type = ? AND forgotten = 0 "
          + "AND embedding IS NOT NULL ORDER BY vec_distance_L2(embedding, ?) LIMIT ?")
        .params(profileId, incoming.type().wire(), encodeEmbedding(embedding), channelLimit).query(String.class).list());
    }
    return ids.stream().flatMap(id -> revisions(profileId, id).stream())
      .filter(r -> r.recordedTo() == null && !r.invalidated()).distinct().limit(limit).toList();
  }

  @Override
  public List<RevisionResponse> conflictCandidates(String profileId, Memory incoming, int limit) {
    return conflictCandidates(profileId, incoming, GraphFragment.empty(), limit);
  }

  private boolean isForgotten(String memoryId) {
    return jdbc.sql("SELECT forgotten FROM memories WHERE id = ?").param(memoryId).query(Integer.class).optional().orElse(1) != 0;
  }

  @Override
  public List<TemporalValidity> selectedValidity(String memoryId, TemporalSnapshot snapshot) {
    return new SqliteTemporalStore(jdbc).selected(memoryId, snapshot);
  }

  @Override
  public List<RevisionResponse> revisions(String profileId, String memoryId) {
    return new SqliteTemporalStore(jdbc).history(profileId, memoryId);
  }

  @Override
  public Optional<FactHistory> factHistory(String profileId, String memoryId,
                                           TemporalSnapshot snapshot) {
    return new SqliteTemporalStore(jdbc).factHistory(profileId, memoryId, snapshot);
  }

  private static byte[] encodeEmbedding(float[] embedding) {
    return dev.alvo.pieria.tools.EmbeddingCodec.encode(embedding);
  }

  /**
   * Inverse of {@link #encodeEmbedding}: decode little-endian float32 bytes back to a float vector.
   */
  private static float[] decodeEmbedding(byte[] blob) {
    return dev.alvo.pieria.tools.EmbeddingCodec.decode(blob);
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

  private static boolean isCodeDerived(String topicKey) {
    return topicKey != null && topicKey.startsWith(CODE_TOPIC_NAMESPACE);
  }

  private static boolean isTraceOutcome(String topicKey) {
    return topicKey != null && topicKey.startsWith(TRACE_OUTCOME_TOPIC_NAMESPACE);
  }

  private static List<Scored> getScoredList(List<Memory> active, List<String> terms, List<String> matchedSessions) {
    Map<String, Scored> scored = new LinkedHashMap<>();

    for (Memory memory : active) {
      String content = memory.content() == null ? "" : memory.content().toLowerCase(Locale.ROOT);
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
      + "WHERE e.profile_id = ? AND " + known("e") + " AND " + active("m") + " AND " + fromColumn + " IN (" + frontierParams + ")"
      + typeFilter;
  }

  /**
   * Degree source rows: every active-edge endpoint, once per incidence. Binds profileId twice.
   */
  private static String degreeRows() {
    return "SELECT e.source_entity_id AS eid FROM edges e JOIN memories m ON m.id = e.memory_id   WHERE e.profile_id = ? AND " + known("e") + " AND " + active("m") + " UNION ALL SELECT e.target_entity_id FROM edges e JOIN memories m ON m.id = e.memory_id   WHERE e.profile_id = ? AND " + known("e") + " AND " + active("m") + "";
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
    new dev.alvo.pieria.cluster.SqliteClusterStore(jdbc,this).deleteProfile(profileId);
    new SqliteWikiStore(jdbc, this).deleteProfile(profileId);
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
    jdbc.sql("DELETE FROM validity_segments WHERE profile_id = ?").param(profileId).update();
    jdbc.sql("DELETE FROM assertion_revisions WHERE assertion_id IN (SELECT id FROM memory_assertions WHERE profile_id = ?)").param(profileId).update();
    jdbc.sql("DELETE FROM memory_assertions WHERE profile_id = ?").param(profileId).update();
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
    return jdbc.sql("SELECT p.id, p.name, p.created_at, COUNT(m.id) FILTER (WHERE " + active("m") + ") AS active_count FROM profiles p LEFT JOIN memories m ON m.profile_id = p.id GROUP BY p.id, p.name, p.created_at ORDER BY p.name")
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
    jdbc.sql("SELECT type, COUNT(*) AS n FROM memories WHERE profile_id = ? AND " + active("") + " GROUP BY type")
      .param(profileId)
      .query((rs, _) -> Map.entry(rs.getString("type"), rs.getLong("n")))
      .list()
      .forEach(e -> byType.put(e.getKey(), e.getValue()));

    long totalActive = byType.values().stream().mapToLong(Long::longValue).sum();

    long superseded = jdbc.sql("SELECT COUNT(*) FROM memories WHERE profile_id = ? AND forgotten = 0 AND NOT (" + active("") + ")")
      .param(profileId)
      .query(Long.class)
      .single();

    long sessions = jdbc.sql(
        "SELECT COUNT(DISTINCT session_id) FROM memories WHERE profile_id = ? AND " + active("") + " AND session_id IS NOT NULL")
      .param(profileId)
      .query(Long.class)
      .single();

    // MIN/MAX over ISO-8601 strings sorts chronologically; null when the profile has no active rows.
    String first = jdbc.sql("SELECT MIN(created_at) FROM memories WHERE profile_id = ? AND " + active("") + "")
      .param(profileId)
      .query(String.class)
      .optional()
      .orElse(null);
    String last = jdbc.sql("SELECT MAX(created_at) FROM memories WHERE profile_id = ? AND " + active("") + "")
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
        + "WHERE profile_id = ? AND " + active("") + " AND id IN (" + placeholders + ")")
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

  /**
   * The active memory this one supersedes by exact topic key, or null. Returns null when the active
   * row IS the incoming memory (identical content-addressed id): a re-ingest must stay idempotent,
   * not supersede the row it would re-insert.
   */

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

    SqliteTemporalStore.Transaction tx = null;
    for (Message message : messages) {
      String id = ContentId.forMessage(profileId, sessionId, message.role(), message.content());
      if (messageIdOwnedByProfile(id, profileId)) continue;
      if (tx == null) tx = new SqliteTemporalStore(jdbc).transaction();
      String createdAt = (message.createdAt() == null ? Instant.now() : message.createdAt()).toString();
      int inserted = jdbc.sql("""
          INSERT OR IGNORE INTO messages \
          (id, profile_id, session_id, role, content, created_at, recorded_at, recorded_sequence) \
          VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(id, profileId, sessionId, message.role(), message.content(), createdAt, TemporalSnapshot.timestamp(tx.at()), tx.sequence())
        .update();
      if (inserted == 0 && !messageIdOwnedByProfile(id, profileId)) {
        throw new IllegalStateException("message id collision across profiles: " + id);
      }
    }
  }

  @Override
  @Transactional
  public Memory insertMemory(String profileId, Memory memory) {
    Memory stored = insertMemoryRecord(profileId, memory, Tokens.estimate(memory.content())).stored();
    new SqliteTemporalStore(jdbc).append(profileId, stored, memory, memory.validFrom(), memory.validTo(), memory.sourceOccurrence(), null);
    return stored;
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

  private String resolveMemoryId(String profileId, Memory memory) {
    if (memory.id() != null) {
      return memory.id();
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
  public StoreOutcome store(String profileId, Memory memory) {
    return store(profileId, memory, GraphFragment.empty(), Tokens.estimate(memory.content()));
  }

  @Override
  @Transactional
  public StoreOutcome store(String profileId, Memory memory, GraphFragment graph) {
    return store(profileId, memory, graph, Tokens.estimate(memory.content()));
  }

  @Override
  @Transactional
  public StoreOutcome store(String profileId, Memory memory, GraphFragment graph, long sourceTokens) {
    jdbc.sql("UPDATE temporal_transactions SET sequence = sequence WHERE sequence = (SELECT MAX(sequence) FROM temporal_transactions)").update();
    String id = resolveMemoryId(profileId, memory);

    // TASK is always append-only, and so is EVENT — EXCEPT for the one keyed EVENT population:
    // trace outcomes (`trace:outcome:<signature>`), keyed so the latest run of a command supersedes
    // the previous one (execution-trace-memory design D5) instead of accumulating one row per run.
    // A conversational EVENT never uses this topic-key namespace, so it stays append-only.
    boolean keyedSupersedable = memory.type() == MemoryType.FACT
      || memory.type() == MemoryType.INSTRUCTION
      || (memory.type() == MemoryType.EVENT && isTraceOutcome(memory.topicKey()));
    // Near-duplicate-content supersession stays FACT/INSTRUCTION-only: it exists to catch a
    // restated fact under a drifted topic key, which is a conversational-extraction problem that
    // does not apply to deterministic, exactly-keyed trace outcomes.
    boolean nearDuplicateSupersedable =
      memory.type() == MemoryType.FACT || memory.type() == MemoryType.INSTRUCTION;

    String supersededId = null;
    if (keyedSupersedable && memory.topicKey() != null) {
      supersededId = activeIdByTopicKey(profileId, memory, id);
    }
    if (supersededId == null && nearDuplicateSupersedable && !memory.semanticChecked() && memory.validFrom() == null && memory.validTo() == null) {
      supersededId = activeIdByNearDuplicateContent(profileId, memory, id);
    }

    // A replayed or back-filled transcript arrives after facts that are already newer than it. Left
    // unchecked the incoming row would supersede them purely because it was stored last, silently
    // replacing current knowledge with older knowledge. When the predecessor was stated later, the
    // incoming memory is the stale one: keep it as history and leave the active row alone.
    boolean staleOnArrival = supersededId != null && statedBefore(memory, supersededId, profileId);
    if (staleOnArrival) {
      log.debug("store keeping memory {} superseded on arrival: {} states the topic later", id, supersededId);
      supersededId = null;
    }

    if (supersededId != null) {
      jdbc.sql("UPDATE memories SET superseded = 1 WHERE id = ?").param(supersededId).update();
    }

    Memory toInsert = new Memory(
      id,
      memory.sessionId(),
      memory.type(),
      memory.content(),
      memory.topicKey(),
      supersededId != null ? supersededId : memory.supersedes(),
      memory.superseded() || staleOnArrival,
      memory.payload(),
      memory.embedText(),
      memory.createdAt());

    MemoryInsert insert = insertMemoryRecord(profileId, toInsert, sourceTokens);
    Memory stored = insert.stored();
    new SqliteTemporalStore(jdbc).append(profileId, stored, memory, memory.validFrom(), memory.validTo(), memory.sourceOccurrence(), supersededId);

    // Persist the extracted graph in the same transaction, tagging each edge with this memory's id
    // as provenance. The fragment is empty for the two-arg store path and for TASK memories.
    persistGraph(profileId, stored.id(), graph);

    boolean enqueuedVector = false;
    // Historical and scheduled assertions retain vectors; only tasks and forgotten content are excluded.
    if (memory.type() != MemoryType.TASK && !isForgotten(stored.id())) {
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

  /**
   * Whether {@code memory} states its topic <em>earlier</em> than the predecessor it would otherwise
   * supersede — i.e. it is older knowledge arriving later.
   *
   * <p>Compared on {@link MemoryTimes#knowledgeTime}, so a transcript that says when it was spoken is
   * ordered by that rather than by when it happened to be ingested. Unknown on either side means no
   * evidence of staleness, and supersession proceeds as before.
   */
  private boolean statedBefore(Memory memory, String predecessorId, String profileId) {
    Instant incoming = MemoryTimes.knowledgeTime(memory);
    Instant existing = memoryByIdAndProfile(predecessorId, profileId)
      .map(MemoryTimes::knowledgeTime)
      .orElse(null);
    return incoming != null && existing != null && incoming.isBefore(existing);
  }

  private String activeIdByTopicKey(String profileId, Memory memory, String id) {
    return jdbc.sql(
        "SELECT id FROM memories WHERE profile_id = ? AND type = ? AND topic_key = ? AND " + active("") + " ORDER BY created_at DESC LIMIT 1")
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

  @Override
  public List<Memory> findNearDuplicateCandidates(
    String profileId, MemoryType type, String content, int limit) {
    String match = toFtsMatch(content);
    if (match == null) {
      return List.of();
    }
    return jdbc.sql(
        "SELECT m.id, m.session_id, m.type, m.content, m.topic_key, m.supersedes, m.superseded, m.payload, m.embed_text, "
          + "m.created_at FROM memories_fts f JOIN memories m ON m.rowid = f.rowid WHERE memories_fts MATCH ? AND m.profile_id "
          + "= ? AND m.type = ? AND " + active("m") + " ORDER BY f.rank LIMIT ?")
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
        "SELECT id, session_id, type, content, topic_key, supersedes, superseded, payload, embed_text, created_at FROM "
          + "memories WHERE profile_id = ? AND type = ? AND topic_key = ? AND " + active("") + " ORDER BY created_at DESC")
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
          WHERE id = ? AND forgotten = 0""")
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

  // ---- sqlite-vec index + FTS5 retrieval channels ----

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
    jdbc.sql("UPDATE temporal_transactions SET sequence = sequence WHERE sequence = (SELECT MAX(sequence) FROM temporal_transactions)").update();
    if (jdbc.sql("SELECT type FROM memories WHERE id = ?").param(memoryId).query(String.class).optional().orElse("task").equals("task")) {
      deleteOutboxRow(memoryId);
      return;
    }
    // Write the BLOB first, mirror it into the sqlite-vec index, then remove the outbox row, all in
    // one transaction. encodeEmbedding throws on a null vector before any write happens.
    if (isForgotten(memoryId)) {
      deleteOutboxRow(memoryId);
      return;
    }
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
    sql.append(" AND forgotten = 0");
    if (!includeSuperseded) {
      sql.append(" AND " + active("") + "");
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

    List<Memory> listed = jdbc.sql(sql.toString())
      .params(params)
      .query((rs, _) -> mapMemory(rs))
      .list();
    return listed.stream().filter(memory -> memory.type()!=MemoryType.CLUSTER ||
      eligibleClusterMemory(profileId,memory.id(),SNAPSHOT.isBound()?SNAPSHOT.get():null,false)).toList();
  }

  @Override
  @Transactional
  public boolean forgetMemory(String profileId, String memoryId) {
    int affected = jdbc.sql("UPDATE memories SET forgotten = 1, superseded = 1, embedding = NULL WHERE id = ? AND profile_id = ? AND forgotten = 0")
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
          WHERE profile_id = ? AND forgotten = 0 ORDER BY created_at DESC""")
      .param(profileId)
      .query((rs, _) -> mapMemory(rs))
      .list();

    List<ExportRow> rows = new ArrayList<>(memories.size());
    for (Memory memory : memories) {
      if (memory.type()==MemoryType.CLUSTER && !eligibleClusterMemory(profileId,memory.id(),null,false)) continue;
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
    for (String token : query.toLowerCase(Locale.ROOT).split("\\s+")) {
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

  private @NonNull List<String> getMatchedSessions(String profileId, List<String> terms) {
    // Session ids that have a message matching any term: lets message hits surface their memories.
    return terms.stream().flatMap(term -> jdbc
        .sql("SELECT DISTINCT session_id FROM messages WHERE profile_id = ? AND " + known("messages") + " AND lower(content) LIKE ?")
        .params(profileId, "%" + term + "%")
        .query(String.class)
        .list().stream())
      .filter(Objects::nonNull)
      .distinct()
      .toList();
  }

  @Override
  public boolean isVectorSearchAvailable() {
    return vectorEnabled && vecCapability != null && vecCapability.isLoaded();
  }

  @Override
  @Transactional
  public void upsertEmbedding(String memoryId, float[] embedding) {
    if (!isVectorSearchAvailable() || embedding == null || isForgotten(memoryId)) {
      return;
    }
    // vec0 has no UPSERT; delete-then-insert keeps memory_id unique and idempotent.
    jdbc.sql("DELETE FROM memories_vec WHERE memory_id = ?").param(memoryId).update();
    jdbc.sql("INSERT INTO memories_vec (memory_id, embedding) VALUES (?, ?)")
      .params(memoryId, toVecJson(embedding))
      .update();
  }

  // ---- entity-relation graph ----

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
    int pageSize=Math.min(100,Math.max(20,limit));
    List<Memory> eligible=new ArrayList<>();
    for(int offset=0;offset<300 && eligible.size()<limit;offset+=pageSize) {
      List<Memory> page=jdbc.sql(
          "SELECT m.id, m.session_id, m.type, m.content, m.topic_key, m.supersedes, m.superseded, m.payload, m.embed_text, "
            + "m.created_at FROM memories_fts f JOIN memories m ON m.rowid = f.rowid WHERE memories_fts MATCH ? AND m.profile_id "
            + "= ? AND " + active("m") + " ORDER BY f.rank,m.id LIMIT ? OFFSET ?")
        .params(match,profileId,Math.min(pageSize,300-offset),offset)
        .query((rs, _) -> mapMemory(rs)).list();
      for(Memory memory:page) if(memory.type()!=MemoryType.CLUSTER ||
          eligibleClusterMemory(profileId,memory.id(),SNAPSHOT.isBound()?SNAPSHOT.get():null,false)) {
        eligible.add(memory);
        if(eligible.size()==limit) break;
      }
      if(page.size()<pageSize) break;
    }
    return List.copyOf(eligible);
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
        "SELECT m.id, m.session_id, m.type, m.content, m.topic_key, m.supersedes, m.superseded, m.payload, m.embed_text, "
            + "m.created_at FROM memories m JOIN (   SELECT msg.session_id AS sid, MIN(f.rank) AS best_rank   FROM messages_fts f "
            // The recorded-time predicates otherwise make SQLite drive this join from messages
            // and re-scan the FTS5 table once per profile message. CROSS JOIN pins FTS5 first.
            + "  CROSS JOIN messages msg ON msg.rowid = f.rowid   WHERE messages_fts MATCH ? AND msg.profile_id = ? AND " + known("msg") + "   GROUP BY msg.session_id ) hit ON hit.sid = m.session_id WHERE m.profile_id = ? AND " + active("m") + " ORDER BY hit.best_rank, m.created_at DESC LIMIT ?")
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
          + "WHERE profile_id = ? AND type IN (?, ?) AND " + active("") + " "
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
    // Rank only eligible stored blobs. KNN-then-filter can starve a historical/profile query.
    int pageSize=Math.min(100,Math.max(20,limit));
    byte[] encoded=encodeEmbedding(queryEmbedding);
    List<Memory> eligible=new ArrayList<>();
    for(int offset=0;offset<300 && eligible.size()<limit;offset+=pageSize) {
      List<Memory> page=jdbc.sql("SELECT " + MEMORY_COLUMNS + " FROM memories WHERE profile_id = ? AND " + active("")
          + " AND type != ? AND embedding IS NOT NULL ORDER BY vec_distance_L2(embedding, ?), id LIMIT ? OFFSET ?")
        .params(profileId,MemoryType.TASK.wire(),encoded,Math.min(pageSize,300-offset),offset)
        .query((rs, _) -> mapMemory(rs)).list();
      for(Memory memory:page) if(memory.type()!=MemoryType.CLUSTER ||
          eligibleClusterMemory(profileId,memory.id(),SNAPSHOT.isBound()?SNAPSHOT.get():null,false)) {
        eligible.add(memory);
        if(eligible.size()==limit) break;
      }
      if(page.size()<pageSize) break;
    }
    return List.copyOf(eligible);
  }

  @Override
  public Map<String, float[]> embeddingsFor(String profileId, Collection<String> memoryIds) {
    if (memoryIds == null || memoryIds.isEmpty()) {
      return Map.of();
    }
    // Distinct ids keep the IN-list (and the placeholder count) tight when a caller passes repeats.
    List<String> ids = memoryIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
    List<Object> params = new ArrayList<>(ids.size() + 1);
    params.add(profileId);
    params.addAll(ids);

    record Row(String id, byte[] embedding) {
    }
    List<Row> rows = jdbc.sql(
        "SELECT id, embedding FROM memories WHERE profile_id = ? AND embedding IS NOT NULL "
          + "AND id IN (" + placeholders + ")")
      .params(params)
      .query((rs, _) -> new Row(rs.getString("id"), rs.getBytes("embedding")))
      .list();

    Map<String, float[]> out = new HashMap<>(rows.size());
    for (Row row : rows) {
      if (row.embedding() != null && row.embedding().length > 0) {
        out.put(row.id(), decodeEmbedding(row.embedding()));
      }
    }
    return out;
  }

  @Override
  public int backfillVectors() {
    if (!isVectorSearchAvailable()) {
      return 0;
    }
    // vec0 does not make the memory_id equality predicate an efficient lookup. A correlated
    // NOT EXISTS scans the vector table for each embedded memory, which took 38 seconds on a
    // populated store even when nothing needed backfilling. Scan vec0 once, then find missing
    // IDs from the ordinary indexed table. Do not hold a write transaction across either scan:
    // startup backfill must not block HTTP, audit, and outbox writes for its whole duration.
    Set<String> indexed = new HashSet<>(jdbc.sql("SELECT memory_id FROM memories_vec")
      .query(String.class).list());
    List<String> missing = jdbc.sql(
        "SELECT id FROM memories WHERE forgotten = 0 AND type != ? AND embedding IS NOT NULL")
      .param(MemoryType.TASK.wire())
      .query(String.class).list().stream()
      .filter(id -> !indexed.contains(id))
      .toList();
    int count = 0;
    for (String id : missing) {
      byte[] embedding = jdbc.sql("SELECT embedding FROM memories WHERE id = ? AND forgotten = 0")
        .param(id).query(byte[].class).optional().orElse(null);
      if (embedding == null || embedding.length == 0) {
        continue;
      }
      // The index is derived and idempotent. If interrupted between its delete and insert,
      // the next startup sees the absent ID and repairs it.
      upsertEmbedding(id, decodeEmbedding(embedding));
      count++;
    }
    return count;
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
    return jdbc.sql("SELECT id, session_id, type, content, topic_key, supersedes, superseded, payload, embed_text, created_at FROM "
        + "memories m WHERE m.profile_id = ? AND " + active("m") + " AND m.type != ? AND m.graph_adopted_at IS NULL AND NOT EXISTS (SELECT 1 FROM edges e WHERE e.memory_id = m.id) ORDER BY m.created_at ASC LIMIT ?")
      .params(profileId, MemoryType.TASK.wire(), limit)
      .query((rs, _) -> mapMemory(rs))
      .list();
  }

  @Override
  public long countGraphOrphans(String profileId) {
    Long count = jdbc.sql("SELECT COUNT(*) FROM memories m WHERE m.profile_id = ? AND " + active("m") + " AND m.type != ? AND m.graph_adopted_at IS NULL AND NOT EXISTS (SELECT 1 FROM edges e WHERE e.memory_id = m.id)")
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
    String placeholders = String.join(",", Collections.nCopies(sessionIds.size(), "?"));
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.add(MemoryType.TASK.wire());
    params.addAll(sessionIds);
    params.add(limit);
    return jdbc.sql(("SELECT id, session_id, type, content, topic_key, supersedes, superseded, payload, embed_text, created_at\nFROM "
        + "memories m\nWHERE m.profile_id = ? AND " + active("m") + " AND m.type != ? AND m.graph_adopted_at IS NULL\nAND m.session_id IN (%s)\nAND NOT EXISTS (SELECT 1 FROM edges e "
        + "WHERE e.memory_id = m.id)\nORDER BY m.created_at ASC\nLIMIT ?").formatted(placeholders))
      .params(params)
      .query((rs, _) -> mapMemory(rs))
      .list();
  }

  @Override
  public long countGraphOrphans(String profileId, List<String> sessionIds) {
    if (sessionIds == null || sessionIds.isEmpty()) {
      return 0L;
    }
    String placeholders = String.join(",", Collections.nCopies(sessionIds.size(), "?"));
    List<Object> params = new ArrayList<>();
    params.add(profileId);
    params.add(MemoryType.TASK.wire());
    params.addAll(sessionIds);
    Long count = jdbc.sql(("SELECT COUNT(*) FROM memories m\nWHERE m.profile_id = ? AND " + active("m") + " AND m.type != ? AND m.graph_adopted_at IS NULL\nAND m.session_id IN (%s)\nAND NOT EXISTS (SELECT 1 FROM edges e WHERE e.memory_id = m.id)").formatted(placeholders))
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
    if (jdbc.sql("SELECT COUNT(*) FROM edges WHERE id = ? AND profile_id = ?").params(id, profileId).query(Integer.class).single() > 0) {
      return new Edge(id, profileId, edge.sourceEntityId(), edge.targetEntityId(), edge.relation(), edge.memoryId(), edge.createdAt());
    }
    Instant createdAt = edge.createdAt() == null ? Instant.now() : edge.createdAt();
    var tx = new SqliteTemporalStore(jdbc).transaction();

    jdbc.sql("""
        INSERT OR IGNORE INTO edges \
        (id, profile_id, source_entity_id, target_entity_id, relation, memory_id, created_at, recorded_at, recorded_sequence) \
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
      .params(id, profileId, edge.sourceEntityId(), edge.targetEntityId(), edge.relation(),
        edge.memoryId(), createdAt.toString(), TemporalSnapshot.timestamp(tx.at()), tx.sequence())
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
        + "WHERE en.profile_id = ? AND " + known("e") + " AND " + active("m") + " "
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

  // ---- graph explorer reads ------------------------------------------------------------------
  //
  // Every query below reaches the edges table through an equality predicate that idx_edge_source or
  // idx_edge_target can serve. Correlating entities and edges with a single
  // `ON (source = id OR target = id)` predicate instead defeats both indexes and degrades to a full
  // memories scan per entity — on a 45k-edge profile that is the difference between 60ms and 180s.

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
        + "    WHERE e.profile_id = ? AND " + known("e") + " AND e.source_entity_id IN (" + placeholders + ") "
        + "  UNION "
        + "  SELECT e.memory_id AS mid, e.target_entity_id AS eid FROM edges e "
        + "    WHERE e.profile_id = ? AND " + known("e") + " AND e.target_entity_id IN (" + placeholders + ") "
        + ") x JOIN memories m ON m.id = x.mid WHERE " + active("m") + "")
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
        + "WHERE m.profile_id = ? AND " + active("m") + " AND je.value IN (" + placeholders + ") "
        + "GROUP BY m.id ORDER BY m.created_at DESC LIMIT ?")
      .params(params)
      .query((rs, _) -> mapMemory(rs))
      .list();
  }

  @Override
  public GraphCounts graphCounts(String profileId) {
    int edges = jdbc.sql("SELECT COUNT(*) FROM edges e JOIN memories m ON m.id = e.memory_id "
        + "WHERE e.profile_id = ? AND " + known("e") + " AND " + active("m") + "")
      .param(profileId)
      .query(Integer.class)
      .single();

    int entities = jdbc.sql("SELECT COUNT(*) FROM entities en WHERE en.profile_id = ? "
        + "AND en.id IN (" + connectedEntityIds() + ")")
      .params(profileId, profileId, profileId)
      .query(Integer.class)
      .single();

    return new GraphCounts(entities, edges);
  }

  @Override
  public Map<String, Integer> entityTypeCounts(String profileId) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    jdbc.sql("SELECT type, COUNT(*) AS c FROM entities WHERE profile_id = ? AND " + activeEntity("entities") + " "
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
        + "WHERE profile_id = ? AND " + activeEntity("entities") + " AND name LIKE ? ESCAPE '\\'" + typeClause + " "
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
        + "WHERE e.profile_id = ? AND " + known("e") + " AND " + active("m") + " "
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
        + "WHERE profile_id = ? AND " + activeEntity("entities") + " AND id IN (" + placeholders + ")")
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
        + "    WHERE e.profile_id = ? AND " + known("e") + " AND " + active("m") + " "
        + "    AND e.source_entity_id IN (" + placeholders + ") "
        + "  UNION ALL "
        + "  SELECT e.target_entity_id FROM edges e JOIN memories m ON m.id = e.memory_id "
        + "    WHERE e.profile_id = ? AND " + known("e") + " AND " + active("m") + " "
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
        + "    WHERE e.profile_id = ? AND " + known("e") + " AND " + active("m") + " AND e.source_entity_id = ? "
        + "  UNION ALL "
        + "  SELECT " + EDGE_COLUMNS + ", 0 AS outgoing, e.source_entity_id AS other_id "
        + "    FROM edges e JOIN memories m ON m.id = e.memory_id "
        + "    WHERE e.profile_id = ? AND " + known("e") + " AND " + active("m") + " AND e.target_entity_id = ? "
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

  private record MemoryInsert(Memory stored, boolean inserted) {
  }

  private record Scored(Memory memory, int score, String source) {
  }
}
