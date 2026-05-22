-- Pieria embedded SQLite schema (SPEC 5.2).
-- Phase 1 creates all memory columns up front so Phases 2-3 add behavior without schema churn.
-- FTS5 (memories_fts/messages_fts) and sqlite-vec (memories_vec) virtual tables are deferred to Phase 3.

CREATE TABLE profiles
(
  id         TEXT PRIMARY KEY, -- uuid
  name       TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL     -- ISO-8601
);

CREATE TABLE messages
(
  id         TEXT PRIMARY KEY, -- hex of SHA-256(session+role+content)[:16]
  profile_id TEXT NOT NULL REFERENCES profiles (id),
  session_id TEXT NOT NULL,
  role       TEXT NOT NULL,
  content    TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX idx_msg_profile_session ON messages (profile_id, session_id);

CREATE TABLE memories
(
  id         TEXT PRIMARY KEY,              -- content-addressed
  profile_id TEXT    NOT NULL REFERENCES profiles (id),
  session_id TEXT,
  type       TEXT    NOT NULL,              -- 'fact'|'event'|'instruction'|'task'
  content    TEXT    NOT NULL,              -- canonical declarative statement
  topic_key  TEXT,                          -- normalized key (facts/instructions); Phase 2
  supersedes TEXT REFERENCES memories (id), -- forward pointer in version chain; Phase 2
  superseded INTEGER NOT NULL DEFAULT 0,    -- 0/1
  payload    TEXT    NOT NULL DEFAULT '{}', -- JSON (json1/jsonb funcs)
  embed_text TEXT,                          -- declarative + interrogative queries; Phase 2
  created_at TEXT    NOT NULL
);

-- Keyed lookup / type filter over the active (non-superseded) set.
CREATE INDEX idx_mem_profile_key ON memories (profile_id, topic_key) WHERE superseded = 0;
CREATE INDEX idx_mem_profile_type ON memories (profile_id, type) WHERE superseded = 0;

CREATE TABLE vectorization_outbox
(
  memory_id   TEXT PRIMARY KEY REFERENCES memories (id),
  enqueued_at TEXT    NOT NULL,
  attempts    INTEGER NOT NULL DEFAULT 0
);
