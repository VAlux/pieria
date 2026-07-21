-- Append-only per-profile audit history. profile_id is nullable so failed calls against an unknown
-- profile name can still be recorded; profile_name is always snapshotted for lookup and display.
CREATE TABLE profile_audit_events (
  id                    TEXT PRIMARY KEY,
  profile_id            TEXT REFERENCES profiles (id),
  profile_name          TEXT NOT NULL,
  event_type            TEXT NOT NULL, -- http | task_terminal
  operation             TEXT NOT NULL,
  request_id            TEXT NOT NULL,
  parent_request_id     TEXT,
  task_id               TEXT,
  session_id            TEXT,
  resource_id           TEXT,
  client                TEXT NOT NULL,
  harness               TEXT,
  channel               TEXT NOT NULL,
  client_version        TEXT,
  server_version        TEXT,
  remote_address        TEXT,
  method                TEXT,
  path                  TEXT,
  query_string          TEXT,
  request_media_type    TEXT,
  response_media_type   TEXT,
  started_at            TEXT NOT NULL,
  completed_at          TEXT NOT NULL,
  duration_ms           INTEGER NOT NULL,
  http_status           INTEGER,
  outcome               TEXT NOT NULL,
  error_kind            TEXT,
  error_message         TEXT,
  metadata              TEXT NOT NULL DEFAULT '{}',
  request_body          TEXT NOT NULL DEFAULT '',
  request_bytes         INTEGER NOT NULL DEFAULT 0,
  request_sha256        TEXT NOT NULL,
  request_truncated     INTEGER NOT NULL DEFAULT 0,
  response_body         TEXT NOT NULL DEFAULT '',
  response_bytes        INTEGER NOT NULL DEFAULT 0,
  response_sha256       TEXT NOT NULL,
  response_truncated    INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_audit_profile_time ON profile_audit_events (profile_name, completed_at DESC, id DESC);
CREATE INDEX idx_audit_profile_operation ON profile_audit_events (profile_name, operation, completed_at DESC);
CREATE INDEX idx_audit_profile_caller ON profile_audit_events (profile_name, client, harness, completed_at DESC);
CREATE INDEX idx_audit_profile_outcome ON profile_audit_events (profile_name, outcome, http_status, completed_at DESC);
CREATE INDEX idx_audit_request ON profile_audit_events (request_id);
CREATE INDEX idx_audit_task ON profile_audit_events (task_id);
CREATE INDEX idx_audit_session ON profile_audit_events (profile_name, session_id);

CREATE VIRTUAL TABLE profile_audit_fts USING fts5(
  operation, client, harness, channel, session_id, resource_id, path,
  request_body, response_body, error_message,
  content='profile_audit_events', content_rowid='rowid', tokenize='porter');

CREATE TRIGGER profile_audit_ai AFTER INSERT ON profile_audit_events BEGIN
  INSERT INTO profile_audit_fts(
    rowid, operation, client, harness, channel, session_id, resource_id, path,
    request_body, response_body, error_message)
  VALUES (
    new.rowid, new.operation, new.client, new.harness, new.channel, new.session_id,
    new.resource_id, new.path, new.request_body, new.response_body, new.error_message);
END;

CREATE TRIGGER profile_audit_ad AFTER DELETE ON profile_audit_events BEGIN
  INSERT INTO profile_audit_fts(
    profile_audit_fts, rowid, operation, client, harness, channel, session_id, resource_id,
    path, request_body, response_body, error_message)
  VALUES (
    'delete', old.rowid, old.operation, old.client, old.harness, old.channel, old.session_id,
    old.resource_id, old.path, old.request_body, old.response_body, old.error_message);
END;
