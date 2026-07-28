-- Pieria embedded SQLite schema — consolidated baseline.
--
-- This file replaces the original V1..V14 chain, squashed before the first external release. It is
-- the complete schema, not an incremental step: column order matches what the old chain produced
-- (later ALTERs appended columns, and V13's DROP/RENAME left profile_usage in the order below), so
-- a database created here is byte-for-byte equivalent to one migrated through the old sequence.
--
-- The sqlite-vec `memories_vec` virtual table is deliberately absent. A `vec0` virtual table needs
-- the native extension loaded at migration time, which would crash startup wherever the extension
-- is unavailable; it is created programmatically by SqliteVectorIndex only when the extension loads.
--
-- The Postgres server backend (Phase 6) mirrors this logical model; its dialect-specific migration
-- is deferred to that phase.

-- ═══ profiles ═══════════════════════════════════════════════════════════════════════════════
CREATE TABLE profiles
(
  id         TEXT PRIMARY KEY, -- uuid
  name       TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL     -- ISO-8601
);

-- ═══ messages ═══════════════════════════════════════════════════════════════════════════════
-- Raw conversation turns, retained as retrieval evidence and as the provenance behind
-- memories.source_tokens.
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

-- ═══ memories ═══════════════════════════════════════════════════════════════════════════════
-- `embedding` holds the raw vector as little-endian float32 bytes (4 per dimension);
-- completeVectorization writes it and supersession NULLs it for the superseded row.
--
-- `graph_adopted_at` is the orphan-adoption ("reminiscence") marker: NULL = never scanned for
-- entities/edges; a non-null ISO-8601 timestamp = scanned, so it is never re-extracted even if it
-- legitimately yielded nothing. See ReminiscenceService.
--
-- `source_tokens` is provenance-grounded token savings: how many raw source tokens the memory was
-- distilled from. Populated at ingest with a proportional slice of the source chunk's raw message
-- tokens (chunk tokens / memories that chunk produced), so the per-ingest sum never exceeds what was
-- actually fed in. This is the counterfactual the impact panel reports against — what re-reading the
-- original material would have cost. Same chars/4 heuristic as the Tokens util.
CREATE TABLE memories
(
  id               TEXT PRIMARY KEY,              -- content-addressed
  profile_id       TEXT    NOT NULL REFERENCES profiles (id),
  session_id       TEXT,
  type             TEXT    NOT NULL,              -- 'fact'|'event'|'instruction'|'task'
  content          TEXT    NOT NULL,              -- canonical declarative statement
  topic_key        TEXT,                          -- normalized key (facts/instructions)
  supersedes       TEXT REFERENCES memories (id), -- forward pointer in version chain
  superseded       INTEGER NOT NULL DEFAULT 0,    -- 0/1
  payload          TEXT    NOT NULL DEFAULT '{}', -- JSON (json1/jsonb funcs)
  embed_text       TEXT,                          -- declarative + interrogative queries
  created_at       TEXT    NOT NULL,
  embedding        BLOB,                          -- little-endian float32, 4 bytes per dimension
  graph_adopted_at TEXT,                          -- ISO-8601; NULL = never graph-adopted
  source_tokens    INTEGER NOT NULL DEFAULT 0     -- raw source tokens this memory was distilled from
);

-- Keyed lookup / type filter over the active (non-superseded) set.
CREATE INDEX idx_mem_profile_key ON memories (profile_id, topic_key) WHERE superseded = 0;
CREATE INDEX idx_mem_profile_type ON memories (profile_id, type) WHERE superseded = 0;

-- Supports the orphan finder's active/unadopted scan (SqliteMemoryStore.findGraphOrphans).
CREATE INDEX idx_mem_graph_adopted ON memories (profile_id, created_at)
  WHERE superseded = 0 AND graph_adopted_at IS NULL;

CREATE TABLE vectorization_outbox
(
  memory_id   TEXT PRIMARY KEY REFERENCES memories (id),
  enqueued_at TEXT    NOT NULL,
  attempts    INTEGER NOT NULL DEFAULT 0
);

-- ═══ full-text search ═══════════════════════════════════════════════════════════════════════
-- External-content FTS5 tables (the content lives in the base tables; the FTS table stores only the
-- index) with the Porter stemmer. Triggers keep the index synchronized: deletes/updates use the
-- special 'delete' command insert so the old row leaves the index before the new one lands.

CREATE VIRTUAL TABLE memories_fts USING fts5(
  content,
  content='memories',
  content_rowid='rowid',
  tokenize='porter');

CREATE TRIGGER memories_ai AFTER INSERT ON memories BEGIN
  INSERT INTO memories_fts(rowid, content) VALUES (new.rowid, new.content);
END;

CREATE TRIGGER memories_ad AFTER DELETE ON memories BEGIN
  INSERT INTO memories_fts(memories_fts, rowid, content) VALUES ('delete', old.rowid, old.content);
END;

CREATE TRIGGER memories_au AFTER UPDATE ON memories BEGIN
  INSERT INTO memories_fts(memories_fts, rowid, content) VALUES ('delete', old.rowid, old.content);
  INSERT INTO memories_fts(rowid, content) VALUES (new.rowid, new.content);
END;

CREATE VIRTUAL TABLE messages_fts USING fts5(
  content,
  content='messages',
  content_rowid='rowid',
  tokenize='porter');

CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN
  INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
END;

CREATE TRIGGER messages_ad AFTER DELETE ON messages BEGIN
  INSERT INTO messages_fts(messages_fts, rowid, content) VALUES ('delete', old.rowid, old.content);
END;

CREATE TRIGGER messages_au AFTER UPDATE ON messages BEGIN
  INSERT INTO messages_fts(messages_fts, rowid, content) VALUES ('delete', old.rowid, old.content);
  INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
END;

-- ═══ entity-relation graph ══════════════════════════════════════════════════════════════════
-- Profile-scoped with content-addressed ids (ContentId.forEntity / forEdge), so re-ingest is
-- idempotent via INSERT OR IGNORE. An edge is "active" only while its source memory is active
-- (memories.superseded = 0); edges are never physically deleted on supersession, so all graph
-- queries join memories and filter superseded = 0 at read time.

CREATE TABLE entities (
  id         TEXT PRIMARY KEY,          -- SHA-256(profile_id + type + normalized_name)[:128]
  profile_id TEXT NOT NULL REFERENCES profiles (id),
  type       TEXT NOT NULL,             -- normalized: person | project | tool | file | concept ...
  name       TEXT NOT NULL,             -- normalized entity name (lowercased, collapsed, aliased)
  payload    TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL              -- ISO-8601
);

CREATE UNIQUE INDEX idx_entity_profile_type_name ON entities (profile_id, type, name);
CREATE INDEX idx_entity_profile_name ON entities (profile_id, name);

CREATE TABLE edges (
  id               TEXT PRIMARY KEY,    -- SHA-256(profile_id + source + relation + target + memory_id)[:128]
  profile_id       TEXT NOT NULL REFERENCES profiles (id),
  source_entity_id TEXT NOT NULL REFERENCES entities (id),
  target_entity_id TEXT NOT NULL REFERENCES entities (id),
  relation         TEXT NOT NULL,       -- normalized relation label
  memory_id        TEXT NOT NULL REFERENCES memories (id),  -- provenance: edge active iff this memory is active
  created_at       TEXT NOT NULL
);

CREATE INDEX idx_edge_source ON edges (profile_id, source_entity_id);
CREATE INDEX idx_edge_target ON edges (profile_id, target_entity_id);
CREATE INDEX idx_edge_memory ON edges (memory_id);

-- ═══ source-code intelligence index ═════════════════════════════════════════════════════════
-- An exhaustive symbol-and-edge substrate, profile-scoped with content-addressed ids (ContentId
-- .forCodeFile / forCodeSymbol / forCodeEdge / forCodeModule), so re-index is idempotent via
-- INSERT OR IGNORE. Distinct from the entities/edges graph above: code_edges carry a confidence
-- flag and are provenanced to a FILE (active while the file is in the index, replaced wholesale on
-- re-index), not to a memory. The curated cross-domain projection still reuses entities/edges.
--
-- code_files.id is path-stable (hash of profile + repo_rel_path, NOT the content) so symbols/edges
-- foreign-key a stable file row across edits; the content version lives in content_hash, which the
-- daemon compares to skip unchanged files and which derived memories are content-addressed by.

CREATE TABLE code_modules (
  id         TEXT PRIMARY KEY,          -- SHA-256(profile_id + path)[:128]
  profile_id TEXT NOT NULL REFERENCES profiles (id),
  name       TEXT NOT NULL,             -- display label, e.g. "daemon"
  path       TEXT NOT NULL,             -- repo-relative module root (identity component)
  created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_code_module_profile_path ON code_modules (profile_id, path);

CREATE TABLE code_files (
  id            TEXT PRIMARY KEY,        -- SHA-256(profile_id + repo_rel_path)[:128] (path-stable)
  profile_id    TEXT NOT NULL REFERENCES profiles (id),
  language      TEXT NOT NULL,           -- language pack id, e.g. "java" ("" / unknown ⇒ no symbols)
  repo_rel_path TEXT NOT NULL,
  content_hash  TEXT NOT NULL,           -- content version (drives unchanged-skip)
  loc           INTEGER NOT NULL DEFAULT 0,
  module_id     TEXT REFERENCES code_modules (id),
  indexed_at    TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_code_file_profile_path ON code_files (profile_id, repo_rel_path);
CREATE INDEX idx_code_file_profile_module ON code_files (profile_id, module_id);

CREATE TABLE code_symbols (
  id               TEXT PRIMARY KEY,     -- SHA-256(profile_id + file_id + kind + qualified_name + signature)[:128]
  profile_id       TEXT NOT NULL REFERENCES profiles (id),
  file_id          TEXT NOT NULL REFERENCES code_files (id),
  kind             TEXT NOT NULL,        -- module | class | method | function | field | endpoint | config-key | test ...
  name             TEXT NOT NULL,
  qualified_name   TEXT NOT NULL,        -- best-effort FQN
  signature        TEXT,
  visibility       TEXT,
  start_line       INTEGER NOT NULL DEFAULT 0,
  end_line         INTEGER NOT NULL DEFAULT 0,
  language         TEXT NOT NULL,
  parent_symbol_id TEXT,
  path             TEXT NOT NULL          -- denormalized code_files.repo_rel_path for the FTS index
);

CREATE INDEX idx_code_symbol_profile_qname ON code_symbols (profile_id, qualified_name);
CREATE INDEX idx_code_symbol_profile_name ON code_symbols (profile_id, name);
CREATE INDEX idx_code_symbol_profile_file ON code_symbols (profile_id, file_id);

CREATE TABLE code_edges (
  id            TEXT PRIMARY KEY,        -- SHA-256(profile_id + src_symbol_id + relation + dst_ref + confidence)[:128]
  profile_id    TEXT NOT NULL REFERENCES profiles (id),
  src_symbol_id TEXT NOT NULL REFERENCES code_symbols (id),
  relation      TEXT NOT NULL,           -- calls | references | imports | extends | implements | depends-on | tests | handles-route
  confidence    TEXT NOT NULL,           -- resolved | heuristic
  dst_symbol_id TEXT,                     -- resolved target symbol, or NULL when only the name is known
  dst_ref       TEXT NOT NULL,           -- target name (identity component)
  file_id       TEXT NOT NULL REFERENCES code_files (id)  -- provenance / replace key
);

CREATE INDEX idx_code_edge_profile_src ON code_edges (profile_id, src_symbol_id);
CREATE INDEX idx_code_edge_profile_dst ON code_edges (profile_id, dst_symbol_id);
CREATE INDEX idx_code_edge_profile_rel_conf ON code_edges (profile_id, relation, confidence);
CREATE INDEX idx_code_edge_profile_file ON code_edges (profile_id, file_id);

-- External-content FTS5 over code_symbols. path is denormalized onto code_symbols so symbol search
-- can match on file path.
CREATE VIRTUAL TABLE code_symbols_fts USING fts5(
  name,
  qualified_name,
  signature,
  path,
  content='code_symbols',
  content_rowid='rowid',
  tokenize='porter');

CREATE TRIGGER code_symbols_ai AFTER INSERT ON code_symbols BEGIN
  INSERT INTO code_symbols_fts(rowid, name, qualified_name, signature, path)
  VALUES (new.rowid, new.name, new.qualified_name, new.signature, new.path);
END;

CREATE TRIGGER code_symbols_ad AFTER DELETE ON code_symbols BEGIN
  INSERT INTO code_symbols_fts(code_symbols_fts, rowid, name, qualified_name, signature, path)
  VALUES ('delete', old.rowid, old.name, old.qualified_name, old.signature, old.path);
END;

CREATE TRIGGER code_symbols_au AFTER UPDATE ON code_symbols BEGIN
  INSERT INTO code_symbols_fts(code_symbols_fts, rowid, name, qualified_name, signature, path)
  VALUES ('delete', old.rowid, old.name, old.qualified_name, old.signature, old.path);
  INSERT INTO code_symbols_fts(rowid, name, qualified_name, signature, path)
  VALUES (new.rowid, new.name, new.qualified_name, new.signature, new.path);
END;

-- ═══ per-profile configuration ══════════════════════════════════════════════════════════════
-- Overrides pushed by the CLI from a project's .pieria/config.toml (PUT /v1/profiles/{name}/config)
-- and merged onto the global PieriaProperties at request time by EffectiveConfigResolver. One row
-- per profile holding the whitelisted overrides as canonical kebab-case JSON (TOML is a client-side
-- authoring format only; the daemon never sees it).
CREATE TABLE profile_config (
  profile_id  TEXT PRIMARY KEY REFERENCES profiles (id),
  config_json TEXT NOT NULL,
  updated_at  TEXT NOT NULL              -- ISO-8601
);

-- ═══ usage counters ═════════════════════════════════════════════════════════════════════════
-- Per-profile lifetime token-savings counters ("Pieria impact"). One row per profile, accumulated at
-- event time (recall/ingest) rather than recomputed on read, so the totals are a true lifetime
-- odometer that survives supersession and any future message pruning. All token figures use the
-- shared chars/4 heuristic (Tokens util) — a relative estimate, not billing-grade accounting.
--
-- `tokens_saved` accumulates provenance-grounded source tokens (see memories.source_tokens). It
-- replaced an earlier pair of counters, one of which credited the entire active corpus on every
-- recall and so grew without bound with corpus size x recall count, describing a counterfactual
-- nobody would run.
CREATE TABLE profile_usage (
  profile_id           TEXT PRIMARY KEY REFERENCES profiles (id),
  recall_count         INTEGER NOT NULL DEFAULT 0,
  ingest_count         INTEGER NOT NULL DEFAULT 0,
  tokens_saved         INTEGER NOT NULL DEFAULT 0, -- provenance-grounded source tokens avoided
  tokens_recall_served INTEGER NOT NULL DEFAULT 0, -- synthesized answer tokens (informational)
  tokens_ingested      INTEGER NOT NULL DEFAULT 0, -- raw messages distilled on ingest
  tokens_stored        INTEGER NOT NULL DEFAULT 0, -- memories produced from those messages
  updated_at           TEXT    NOT NULL            -- ISO-8601
);

-- Per-profile real inference token spend, accumulated at event time, one row per model tier.
-- Complements profile_usage (heuristic token *savings*); this is what Pieria actually *spent* on the
-- provider. Tokens are the real prompt/completion counts the provider reports, not the chars/4
-- heuristic. Embedding usage may stay zero for providers (e.g. Ollama) that do not report it over
-- the OpenAI-compatible API.
CREATE TABLE profile_inference_usage (
  profile_id        TEXT    NOT NULL REFERENCES profiles (id),
  tier              TEXT    NOT NULL,           -- EXTRACTION | SYNTHESIS | EMBEDDING
  calls             INTEGER NOT NULL DEFAULT 0, -- number of model calls recorded for the tier
  prompt_tokens     INTEGER NOT NULL DEFAULT 0, -- provider-reported input tokens
  completion_tokens INTEGER NOT NULL DEFAULT 0, -- provider-reported output tokens
  updated_at        TEXT    NOT NULL,           -- ISO-8601 instant of the last accumulation
  PRIMARY KEY (profile_id, tier)
);

-- ═══ incremental-onboarding ledger ══════════════════════════════════════════════════════════
-- One row per processed content document, recording the hash of the pipeline-relevant inputs
-- (pipeline version, extraction samples, provenance, text). A matching hash on re-onboard skips
-- every model call for that document; rows are written only after the document's memories are
-- durably stored, so an interrupted onboard resumes where it left off. Rows for since-deleted
-- documents are harmless leftovers (memories are superseded, never bulk-deleted).
CREATE TABLE ingest_ledger (
  profile_id   TEXT NOT NULL REFERENCES profiles (id),
  scope        TEXT NOT NULL, -- source type: markdown | text | pdf | web
  item_key     TEXT NOT NULL, -- document provenance (repo-relative path or URL)
  content_hash TEXT NOT NULL, -- Hash.hash128 over pipeline version + samples + provenance + text
  processed_at TEXT NOT NULL, -- ISO-8601
  PRIMARY KEY (profile_id, scope, item_key)
);

-- ═══ audit history ══════════════════════════════════════════════════════════════════════════
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

-- No AFTER UPDATE trigger: the audit table is append-only by contract.
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
