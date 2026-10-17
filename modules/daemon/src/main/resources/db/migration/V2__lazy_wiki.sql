-- Derived wiki state. Canonical memories and V1 history are unchanged.
CREATE TABLE wiki_lock (id INTEGER PRIMARY KEY CHECK(id=1));
INSERT INTO wiki_lock VALUES (1);
CREATE TABLE wiki_state (
 profile_id TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
 cursor TEXT NOT NULL DEFAULT '', checkpoint INTEGER NOT NULL DEFAULT 0,
 initialized INTEGER NOT NULL DEFAULT 0, revision INTEGER NOT NULL DEFAULT 0,
 policy TEXT NOT NULL DEFAULT '', next_boundary TEXT, target_sequence INTEGER NOT NULL DEFAULT 0, scan_snapshot TEXT
);
CREATE TABLE wiki_changes (
 sequence INTEGER PRIMARY KEY AUTOINCREMENT, profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
 memory_id TEXT, kind TEXT NOT NULL
);
CREATE INDEX wiki_change_profile ON wiki_changes(profile_id, sequence);
CREATE TABLE wiki_pages (
 profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE, id TEXT NOT NULL,
 kind TEXT NOT NULL, title TEXT NOT NULL, anchor TEXT NOT NULL, parent_id TEXT,
 lifecycle TEXT NOT NULL DEFAULT 'active', revision INTEGER NOT NULL DEFAULT 1,
 scope_revision INTEGER NOT NULL DEFAULT 1, suppressed INTEGER NOT NULL DEFAULT 0,
 current_version TEXT, checked_sequence INTEGER NOT NULL DEFAULT 0, next_boundary TEXT,
 signature TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL,
 PRIMARY KEY(profile_id,id), UNIQUE(profile_id,anchor)
);
CREATE INDEX wiki_page_parent ON wiki_pages(profile_id,parent_id,title,id);
CREATE TABLE wiki_memberships (
 profile_id TEXT NOT NULL, page_id TEXT NOT NULL, memory_id TEXT NOT NULL,
 role TEXT NOT NULL, score REAL NOT NULL DEFAULT 1,
 PRIMARY KEY(profile_id,page_id,memory_id),
 FOREIGN KEY(profile_id,page_id) REFERENCES wiki_pages(profile_id,id) ON DELETE CASCADE
);
CREATE INDEX wiki_member_source ON wiki_memberships(profile_id,memory_id);
CREATE TABLE wiki_redirects (
 profile_id TEXT NOT NULL, source_id TEXT NOT NULL, target_id TEXT NOT NULL,
 PRIMARY KEY(profile_id,source_id),
 FOREIGN KEY(profile_id,source_id) REFERENCES wiki_pages(profile_id,id) ON DELETE CASCADE,
 FOREIGN KEY(profile_id,target_id) REFERENCES wiki_pages(profile_id,id) ON DELETE CASCADE
);
CREATE TABLE wiki_links (
 profile_id TEXT NOT NULL, source_id TEXT NOT NULL, target_id TEXT NOT NULL, kind TEXT NOT NULL,
 PRIMARY KEY(profile_id,source_id,target_id,kind),
 FOREIGN KEY(profile_id,source_id) REFERENCES wiki_pages(profile_id,id) ON DELETE CASCADE,
 FOREIGN KEY(profile_id,target_id) REFERENCES wiki_pages(profile_id,id) ON DELETE CASCADE
);
CREATE TABLE wiki_merge_candidates (
 profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
 left_id TEXT NOT NULL, right_id TEXT NOT NULL, signature TEXT NOT NULL,
 observed_round TEXT NOT NULL, last_seen_round TEXT NOT NULL,
 PRIMARY KEY(profile_id,left_id,right_id)
);
CREATE TABLE wiki_versions (
 profile_id TEXT NOT NULL, id TEXT NOT NULL, page_id TEXT NOT NULL,
 fingerprint TEXT NOT NULL, body TEXT NOT NULL, bundle TEXT NOT NULL,
 suppressed INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL,
 PRIMARY KEY(profile_id,id), UNIQUE(profile_id,page_id,fingerprint),
 FOREIGN KEY(profile_id,page_id) REFERENCES wiki_pages(profile_id,id) ON DELETE CASCADE
);
CREATE TABLE wiki_jobs (
 profile_id TEXT NOT NULL, id TEXT NOT NULL, page_id TEXT NOT NULL, fingerprint TEXT NOT NULL,
 bundle TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'queued', attempts INTEGER NOT NULL DEFAULT 0,
 fence INTEGER NOT NULL DEFAULT 0, lease_until TEXT, available_at TEXT NOT NULL,
 result_version TEXT, error_code TEXT, created_at TEXT NOT NULL,
 PRIMARY KEY(profile_id,id),
 FOREIGN KEY(profile_id,page_id) REFERENCES wiki_pages(profile_id,id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX wiki_live_job ON wiki_jobs(profile_id,page_id,fingerprint) WHERE status IN ('queued','generating');
CREATE INDEX wiki_pending_job ON wiki_jobs(status,available_at,lease_until);
CREATE TABLE wiki_dependencies (
 profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
 owner_kind TEXT NOT NULL, owner_id TEXT NOT NULL, memory_id TEXT NOT NULL,
 PRIMARY KEY(profile_id,owner_kind,owner_id,memory_id)
);
CREATE INDEX wiki_dependency_source ON wiki_dependencies(profile_id,memory_id);
CREATE VIRTUAL TABLE wiki_fts USING fts5(title, anchor, excerpts, profile_id UNINDEXED, page_id UNINDEXED, tokenize='porter');
CREATE TRIGGER wiki_page_delete AFTER DELETE ON wiki_pages BEGIN
 DELETE FROM wiki_fts WHERE profile_id=old.profile_id AND page_id=old.id;
END;
-- Capture at the substrate, including writes outside ingestion and while wiki workers are disabled.
CREATE TRIGGER wiki_memory_insert AFTER INSERT ON memories  BEGIN
 INSERT INTO wiki_changes(profile_id,memory_id,kind) SELECT new.profile_id,new.id,'memory';
END;
CREATE TRIGGER wiki_memory_change AFTER UPDATE OF content,topic_key,payload,embedding ON memories WHEN new.content IS NOT old.content OR new.topic_key IS NOT old.topic_key OR new.payload IS NOT old.payload OR new.embedding IS NOT old.embedding BEGIN
 INSERT INTO wiki_changes(profile_id,memory_id,kind) SELECT new.profile_id,new.id,'memory';
END;
CREATE TRIGGER wiki_revision_insert AFTER INSERT ON assertion_revisions  BEGIN
 INSERT INTO wiki_changes(profile_id,memory_id,kind) SELECT m.profile_id,new.memory_id,'assertion' FROM memories m WHERE m.id=new.memory_id;
END;
CREATE TRIGGER wiki_revision_change AFTER UPDATE ON assertion_revisions  BEGIN
 INSERT INTO wiki_changes(profile_id,memory_id,kind) SELECT m.profile_id,new.memory_id,'assertion' FROM memories m WHERE m.id=new.memory_id;
END;
CREATE TRIGGER wiki_edge_insert AFTER INSERT ON edges  BEGIN
 INSERT INTO wiki_changes(profile_id,memory_id,kind) SELECT new.profile_id,new.memory_id,'graph';
END;
CREATE TRIGGER wiki_edge_delete AFTER DELETE ON edges  BEGIN
 INSERT INTO wiki_changes(profile_id,memory_id,kind) SELECT old.profile_id,old.memory_id,'graph';
END;
CREATE TRIGGER wiki_forget AFTER UPDATE OF forgotten ON memories WHEN new.forgotten=1 AND old.forgotten=0 BEGIN
 INSERT INTO wiki_changes(profile_id,memory_id,kind) VALUES(new.profile_id,new.id,'forget');
 UPDATE wiki_pages SET suppressed=1, title='', signature='' WHERE profile_id=new.profile_id AND anchor NOT LIKE 'directory:%' AND id IN (
  SELECT page_id FROM wiki_memberships WHERE profile_id=new.profile_id AND memory_id=new.id
  UNION SELECT owner_id FROM wiki_dependencies WHERE profile_id=new.profile_id AND owner_kind='page' AND memory_id=new.id);
 DELETE FROM wiki_fts WHERE profile_id=new.profile_id AND page_id IN (SELECT id FROM wiki_pages WHERE profile_id=new.profile_id AND suppressed=1);
 UPDATE wiki_versions SET suppressed=1,body='',bundle='{}' WHERE profile_id=new.profile_id AND id IN (
  SELECT owner_id FROM wiki_dependencies WHERE profile_id=new.profile_id AND owner_kind='version' AND memory_id=new.id);
 UPDATE wiki_jobs SET status='cancelled',bundle='{}',fence=fence+1,error_code='source_forgotten' WHERE profile_id=new.profile_id AND id IN (
  SELECT owner_id FROM wiki_dependencies WHERE profile_id=new.profile_id AND owner_kind='job' AND memory_id=new.id);
END;
