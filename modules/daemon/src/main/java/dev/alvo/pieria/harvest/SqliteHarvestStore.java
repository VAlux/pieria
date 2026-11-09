package dev.alvo.pieria.harvest;

import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Embedded SQLite backend for {@link HarvestStore}, against the V7 schema. */
@Repository
public class SqliteHarvestStore implements HarvestStore {

  private final JdbcClient jdbc;

  public SqliteHarvestStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static String time(Instant value) {
    return TemporalSnapshot.timestamp(value);
  }

  private static Instant instant(String value) {
    return value == null ? null : Instant.parse(value);
  }

  private static String wire(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value) {
    return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
  }

  private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static HarvestSource mapSource(ResultSet rs) throws SQLException {
    return new HarvestSource(rs.getString("id"), rs.getString("profile_id"), rs.getString("harvester_id"),
      rs.getString("config_json"), parse(SourceMode.class, rs.getString("mode")), rs.getLong("interval_seconds"),
      nullableInt(rs, "max_items_per_run"), nullableLong(rs, "max_chars_per_run"),
      parse(SourceStatus.class, rs.getString("status")), rs.getString("cursor_json"), instant(rs.getString("next_run_at")),
      rs.getInt("consecutive_failures"), nullableLong(rs, "sweep_blocked_count"), instant(rs.getString("created_at")));
  }

  private static HarvestRun mapRun(ResultSet rs) throws SQLException {
    return new HarvestRun(rs.getString("id"), rs.getString("source_id"), rs.getInt("dry_run") != 0,
      parse(RunStatus.class, rs.getString("status")), instant(rs.getString("started_at")), instant(rs.getString("finished_at")),
      rs.getLong("items_seen"), rs.getLong("items_changed"), rs.getLong("items_deleted"), rs.getLong("memories_stored"),
      rs.getLong("chars"), rs.getString("error_class"), rs.getString("error_message"), rs.getString("stderr_tail"));
  }

  private static HarvestItem mapItem(ResultSet rs) throws SQLException {
    return new HarvestItem(rs.getString("source_id"), rs.getString("external_id"), rs.getString("session_id"),
      rs.getString("version_hash"), rs.getString("prefix_hash"), rs.getInt("turn_count"), rs.getString("last_seen_run"));
  }

  @Override
  public void createSource(HarvestSource s) {
    jdbc.sql("""
        INSERT INTO harvest_sources (id, profile_id, harvester_id, config_json, mode, interval_seconds, max_items_per_run,
        max_chars_per_run, status, cursor_json, next_run_at, consecutive_failures, sweep_blocked_count, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
      .params(s.id(), s.profileId(), s.harvesterId(), s.configJson(), wire(s.mode()), s.intervalSeconds(), s.maxItemsPerRun(),
        s.maxCharsPerRun(), wire(s.status()), s.cursorJson(), time(s.nextRunAt()), s.consecutiveFailures(),
        s.sweepBlockedCount(), time(s.createdAt()))
      .update();
  }

  @Override
  public Optional<HarvestSource> findSource(String sourceId) {
    return jdbc.sql("SELECT * FROM harvest_sources WHERE id = ?").param(sourceId)
      .query((rs, _) -> mapSource(rs)).optional();
  }

  @Override
  public List<HarvestSource> listSources(String profileId) {
    return jdbc.sql("SELECT * FROM harvest_sources WHERE profile_id = ? ORDER BY id").param(profileId)
      .query((rs, _) -> mapSource(rs)).list();
  }

  @Override
  public List<HarvestSource> dueSources(Instant now, int limit) {
    return jdbc.sql("SELECT * FROM harvest_sources WHERE status = 'active' AND next_run_at <= ? ORDER BY next_run_at, id LIMIT ?")
      .params(time(now), limit)
      .query((rs, _) -> mapSource(rs)).list();
  }

  @Override
  public void saveCursor(String sourceId, String cursorJson) {
    jdbc.sql("UPDATE harvest_sources SET cursor_json = ? WHERE id = ?").params(cursorJson, sourceId).update();
  }

  @Override
  public void scheduleNext(String sourceId, Instant nextRunAt, int consecutiveFailures) {
    jdbc.sql("UPDATE harvest_sources SET next_run_at = ?, consecutive_failures = ? WHERE id = ?")
      .params(time(nextRunAt), consecutiveFailures, sourceId).update();
  }

  @Override
  public void setStatus(String sourceId, SourceStatus status) {
    jdbc.sql("UPDATE harvest_sources SET status = ? WHERE id = ?").params(wire(status), sourceId).update();
  }

  @Override
  public void setSweepBlocked(String sourceId, Long count) {
    jdbc.sql("UPDATE harvest_sources SET sweep_blocked_count = ? WHERE id = ?").params(count, sourceId).update();
  }

  @Override
  @Transactional
  public void deleteSource(String sourceId) {
    jdbc.sql("DELETE FROM harvest_item_memories WHERE source_id = ?").param(sourceId).update();
    jdbc.sql("DELETE FROM harvest_items WHERE source_id = ?").param(sourceId).update();
    jdbc.sql("DELETE FROM harvest_runs WHERE source_id = ?").param(sourceId).update();
    jdbc.sql("DELETE FROM harvest_sources WHERE id = ?").param(sourceId).update();
  }

  @Override
  public void updateSource(HarvestSource s) {
    jdbc.sql("""
        UPDATE harvest_sources SET config_json = ?, mode = ?, interval_seconds = ?, max_items_per_run = ?,
        max_chars_per_run = ?, cursor_json = ? WHERE id = ?""")
      .params(s.configJson(), wire(s.mode()), s.intervalSeconds(), s.maxItemsPerRun(), s.maxCharsPerRun(), s.cursorJson(), s.id())
      .update();
  }

  @Override
  public void setAcceptNextSweep(String sourceId, boolean accept) {
    jdbc.sql("UPDATE harvest_sources SET accept_next_sweep = ? WHERE id = ?").params(accept ? 1 : 0, sourceId).update();
  }

  @Override
  public boolean acceptNextSweep(String sourceId) {
    return jdbc.sql("SELECT accept_next_sweep FROM harvest_sources WHERE id = ?").param(sourceId)
      .query(Integer.class).optional().orElse(0) != 0;
  }

  @Override
  public boolean anySourceUses(String harvesterId) {
    return jdbc.sql("SELECT COUNT(*) FROM harvest_sources WHERE harvester_id = ?").param(harvesterId)
      .query(Long.class).single() > 0;
  }

  @Override
  public String startRun(String sourceId, boolean dryRun) {
    String id = UUID.randomUUID().toString();
    jdbc.sql("INSERT INTO harvest_runs (id, source_id, dry_run, status, started_at) VALUES (?, ?, ?, 'running', ?)")
      .params(id, sourceId, dryRun ? 1 : 0, time(Instant.now())).update();
    return id;
  }

  @Override
  public void finishRun(HarvestRun run) {
    jdbc.sql("""
        UPDATE harvest_runs SET status = ?, finished_at = ?, items_seen = ?, items_changed = ?, items_deleted = ?,
        memories_stored = ?, chars = ?, error_class = ?, error_message = ?, stderr_tail = ? WHERE id = ?""")
      .params(wire(run.status()), time(run.finishedAt()), run.itemsSeen(), run.itemsChanged(), run.itemsDeleted(),
        run.memoriesStored(), run.chars(), run.errorClass(), run.errorMessage(), run.stderrTail(), run.id())
      .update();
  }

  @Override
  @Transactional
  public int interruptRunningRuns(Instant nextRunAt) {
    jdbc.sql("""
        UPDATE harvest_sources SET next_run_at = ?
        WHERE id IN (SELECT source_id FROM harvest_runs WHERE status = 'running' AND dry_run = 0)""")
      .param(time(nextRunAt)).update();
    return jdbc.sql("UPDATE harvest_runs SET status = 'interrupted', finished_at = ? WHERE status = 'running'")
      .param(time(Instant.now())).update();
  }

  @Override
  public List<HarvestRun> listRuns(String sourceId, int limit) {
    return jdbc.sql("SELECT * FROM harvest_runs WHERE source_id = ? ORDER BY started_at DESC, id LIMIT ?")
      .params(sourceId, limit)
      .query((rs, _) -> mapRun(rs)).list();
  }

  @Override
  public Optional<HarvestItem> findItem(String sourceId, String externalId) {
    return jdbc.sql("SELECT * FROM harvest_items WHERE source_id = ? AND external_id = ?").params(sourceId, externalId)
      .query((rs, _) -> mapItem(rs)).optional();
  }

  @Override
  public void upsertItem(HarvestItem item) {
    jdbc.sql("""
        INSERT INTO harvest_items (source_id, external_id, session_id, version_hash, prefix_hash, turn_count, last_seen_run)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (source_id, external_id) DO UPDATE SET session_id = excluded.session_id,
        version_hash = excluded.version_hash, prefix_hash = excluded.prefix_hash, turn_count = excluded.turn_count,
        last_seen_run = excluded.last_seen_run""")
      .params(item.sourceId(), item.externalId(), item.sessionId(), item.versionHash(), item.prefixHash(), item.turnCount(),
        item.lastSeenRun())
      .update();
  }

  @Override
  @Transactional
  public void markSeen(String sourceId, Collection<String> externalIds, String runId) {
    for (String externalId : externalIds) {
      jdbc.sql("UPDATE harvest_items SET last_seen_run = ? WHERE source_id = ? AND external_id = ?")
        .params(runId, sourceId, externalId).update();
    }
  }

  @Override
  public List<HarvestItem> unseenItems(String sourceId, String runId) {
    return jdbc.sql("SELECT * FROM harvest_items WHERE source_id = ? AND last_seen_run IS NOT ? ORDER BY external_id")
      .params(sourceId, runId)
      .query((rs, _) -> mapItem(rs)).list();
  }

  @Override
  public long liveItemCount(String sourceId) {
    return jdbc.sql("SELECT COUNT(*) FROM harvest_items WHERE source_id = ?").param(sourceId).query(Long.class).single();
  }

  @Override
  @Transactional
  public void deleteItem(String sourceId, String externalId) {
    jdbc.sql("DELETE FROM harvest_item_memories WHERE source_id = ? AND external_id = ?").params(sourceId, externalId).update();
    jdbc.sql("DELETE FROM harvest_items WHERE source_id = ? AND external_id = ?").params(sourceId, externalId).update();
  }

  /** A link keeps the revision it was made from, which is how a later user correction is recognized. */
  @Override
  @Transactional
  public void addLinks(String sourceId, String externalId, List<MemoryLink> links) {
    for (MemoryLink link : links) {
      jdbc.sql("""
          INSERT INTO harvest_item_memories (source_id, external_id, memory_id, assertion_id, revision_id)
          VALUES (?, ?, ?, ?, ?)
          ON CONFLICT (source_id, external_id, memory_id, assertion_id) DO NOTHING""")
        .params(sourceId, externalId, link.memoryId(), link.assertionId(), link.revisionId())
        .update();
    }
  }

  @Override
  public List<MemoryLink> links(String sourceId, String externalId) {
    return jdbc.sql("SELECT memory_id, assertion_id, revision_id FROM harvest_item_memories WHERE source_id = ? AND external_id = ?")
      .params(sourceId, externalId)
      .query((rs, _) -> new MemoryLink(rs.getString("memory_id"), rs.getString("assertion_id"), rs.getString("revision_id")))
      .list();
  }

  @Override
  public void removeLinks(String sourceId, String externalId, Collection<String> memoryIds) {
    if (memoryIds.isEmpty()) {
      return;
    }
    var params = new java.util.ArrayList<Object>(List.of(sourceId, externalId));
    params.addAll(memoryIds);
    jdbc.sql("DELETE FROM harvest_item_memories WHERE source_id = ? AND external_id = ? AND memory_id IN ("
        + String.join(",", Collections.nCopies(memoryIds.size(), "?")) + ")")
      .params(params).update();
  }

  @Override
  @Transactional
  public void deleteProfile(String profileId) {
    String sources = "SELECT id FROM harvest_sources WHERE profile_id = ?";
    jdbc.sql("DELETE FROM harvest_item_memories WHERE source_id IN (" + sources + ")").param(profileId).update();
    jdbc.sql("DELETE FROM harvest_items WHERE source_id IN (" + sources + ")").param(profileId).update();
    jdbc.sql("DELETE FROM harvest_runs WHERE source_id IN (" + sources + ")").param(profileId).update();
    jdbc.sql("DELETE FROM harvest_sources WHERE profile_id = ?").param(profileId).update();
  }

  @Override
  public String profileName(String profileId) {
    return jdbc.sql("SELECT name FROM profiles WHERE id = ?").param(profileId).query(String.class).single();
  }
}
