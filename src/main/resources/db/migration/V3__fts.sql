-- Phase 3 step 1 (SPEC 5.2): FTS5 full-text search over memories and messages.
-- External-content FTS5 tables (the content lives in the base tables; the FTS table stores
-- only the index) with the Porter stemmer. Triggers keep the index synchronized.
--
-- Note: the sqlite-vec `memories_vec` virtual table is intentionally NOT created here. A
-- `vec0` virtual table requires the native extension to be loaded at migration time, which
-- would crash startup on machines where the extension is unavailable. It is created
-- programmatically at startup (SqliteVectorIndex) only when the extension loads.

-- ---- memories_fts ----------------------------------------------------------------------
CREATE VIRTUAL TABLE memories_fts USING fts5(
  content,
  content='memories',
  content_rowid='rowid',
  tokenize='porter');

-- External-content sync triggers (standard FTS5 pattern). Deletes/updates use the special
-- 'delete' command insert so the old row is removed from the index before the new one lands.
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

-- ---- messages_fts ----------------------------------------------------------------------
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

-- Populate the index from any rows that already exist (Phase 1 / Phase 2 data) so they are
-- immediately searchable after this migration.
INSERT INTO memories_fts(memories_fts) VALUES ('rebuild');
INSERT INTO messages_fts(messages_fts) VALUES ('rebuild');
