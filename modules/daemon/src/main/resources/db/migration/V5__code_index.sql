-- Phase 13: persistent source-code intelligence index.
--
-- An exhaustive symbol-and-edge substrate, profile-scoped with content-addressed ids (see
-- ContentId.forCodeFile / forCodeSymbol / forCodeEdge / forCodeModule), so re-index is idempotent
-- via INSERT OR IGNORE. Distinct from the Phase 8 entities/edges graph: code_edges carry a
-- confidence flag and are provenanced to a FILE (active while the file is in the index, replaced
-- wholesale on re-index), not to a memory. The curated cross-domain projection still reuses the
-- Phase 8 entities/edges tables; this migration does not touch them.
--
-- code_files.id is path-stable (hash of profile + repo_rel_path, NOT the content) so symbols/edges
-- foreign-key a stable file row across edits; the content version lives in content_hash, which the
-- daemon compares to skip unchanged files and which derived memories are content-addressed by.
--
-- The Postgres server backend (Phase 6) mirrors this logical model; its dialect-specific migration
-- is deferred to that phase.

-- ---- code_modules ----------------------------------------------------------------------
CREATE TABLE code_modules (
  id         TEXT PRIMARY KEY,          -- SHA-256(profile_id + path)[:128]
  profile_id TEXT NOT NULL REFERENCES profiles (id),
  name       TEXT NOT NULL,             -- display label, e.g. "daemon"
  path       TEXT NOT NULL,             -- repo-relative module root (identity component)
  created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_code_module_profile_path ON code_modules (profile_id, path);

-- ---- code_files ------------------------------------------------------------------------
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

-- ---- code_symbols ----------------------------------------------------------------------
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

-- ---- code_edges ------------------------------------------------------------------------
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

-- ---- code_symbols_fts ------------------------------------------------------------------
-- External-content FTS5 over code_symbols (mirrors V3). path is denormalized onto code_symbols so
-- symbol search can match on file path. Triggers keep the index synchronized.
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
