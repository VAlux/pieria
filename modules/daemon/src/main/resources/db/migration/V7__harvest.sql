-- Knowledge harvesters. A source is a configured harvester bound to one profile; an item is one unit
-- at that source; harvest_item_memories records which memories came from which item, which is what
-- lets an edit or deletion at the source reach the memories built from it.
-- The Postgres backend is out of scope for this phase; its logical model would mirror these tables.
CREATE TABLE harvest_sources (
  id                   TEXT PRIMARY KEY,
  profile_id           TEXT    NOT NULL REFERENCES profiles (id),
  harvester_id         TEXT    NOT NULL,
  config_json          TEXT    NOT NULL,
  mode                 TEXT    NOT NULL,
  interval_seconds     INTEGER NOT NULL,
  max_items_per_run    INTEGER,
  max_chars_per_run    INTEGER,
  status               TEXT    NOT NULL,
  cursor_json          TEXT,
  next_run_at          TEXT    NOT NULL,
  consecutive_failures INTEGER NOT NULL DEFAULT 0,
  sweep_blocked_count  INTEGER,
  created_at           TEXT    NOT NULL
);
CREATE INDEX idx_harvest_sources_profile ON harvest_sources (profile_id);
CREATE INDEX idx_harvest_sources_due ON harvest_sources (status, next_run_at);

CREATE TABLE harvest_runs (
  id              TEXT PRIMARY KEY,
  source_id       TEXT    NOT NULL,
  dry_run         INTEGER NOT NULL,
  status          TEXT    NOT NULL,
  started_at      TEXT    NOT NULL,
  finished_at     TEXT,
  items_seen      INTEGER NOT NULL DEFAULT 0,
  items_changed   INTEGER NOT NULL DEFAULT 0,
  items_deleted   INTEGER NOT NULL DEFAULT 0,
  memories_stored INTEGER NOT NULL DEFAULT 0,
  chars           INTEGER NOT NULL DEFAULT 0,
  error_class     TEXT,
  error_message   TEXT,
  stderr_tail     TEXT
);
CREATE INDEX idx_harvest_runs_source ON harvest_runs (source_id, started_at);
CREATE INDEX idx_harvest_runs_status ON harvest_runs (status);

CREATE TABLE harvest_items (
  source_id     TEXT    NOT NULL,
  external_id   TEXT    NOT NULL,
  session_id    TEXT    NOT NULL,
  version_hash  TEXT    NOT NULL,
  prefix_hash   TEXT,
  turn_count    INTEGER NOT NULL DEFAULT 0,
  last_seen_run TEXT,
  PRIMARY KEY (source_id, external_id)
);
CREATE INDEX idx_harvest_items_seen ON harvest_items (source_id, last_seen_run);

CREATE TABLE harvest_item_memories (
  source_id    TEXT NOT NULL,
  external_id  TEXT NOT NULL,
  memory_id    TEXT NOT NULL,
  assertion_id TEXT NOT NULL,
  revision_id  TEXT NOT NULL,
  PRIMARY KEY (source_id, external_id, memory_id, assertion_id)
);
