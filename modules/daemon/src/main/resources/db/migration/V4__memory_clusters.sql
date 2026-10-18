-- Nested, overlapping knowledge containers. All graph changes are serialized through
-- cluster_lock and recorded as immutable structure revisions.
CREATE TABLE cluster_lock (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO cluster_lock(id) VALUES (1);

CREATE TABLE clusters (
  id TEXT PRIMARY KEY,
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  current_revision INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_clusters_profile ON clusters(profile_id);

CREATE TABLE cluster_structure_revisions (
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  revision INTEGER NOT NULL,
  title TEXT NOT NULL,
  scope TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('active','dissolved')),
  recorded_at TEXT NOT NULL,
  recorded_sequence INTEGER NOT NULL,
  actor TEXT NOT NULL,
  reason TEXT NOT NULL,
  PRIMARY KEY(cluster_id, revision)
);
CREATE INDEX idx_cluster_structure_time ON cluster_structure_revisions(cluster_id, recorded_sequence);
CREATE TRIGGER wiki_cluster_structure_insert AFTER INSERT ON cluster_structure_revisions BEGIN
  INSERT INTO wiki_changes(profile_id,memory_id,kind)
    SELECT profile_id,NULL,'cluster_structure' FROM clusters WHERE id=new.cluster_id;
END;

CREATE TABLE cluster_memberships (
  cluster_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  child_kind TEXT NOT NULL CHECK(child_kind IN ('memory','cluster')),
  child_id TEXT NOT NULL,
  PRIMARY KEY(cluster_id, revision, child_kind, child_id),
  FOREIGN KEY(cluster_id, revision) REFERENCES cluster_structure_revisions(cluster_id, revision) ON DELETE CASCADE
);
CREATE INDEX idx_cluster_membership_child ON cluster_memberships(child_kind, child_id);
CREATE TABLE cluster_dependencies (
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  ancestor_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  descendant_kind TEXT NOT NULL CHECK(descendant_kind IN ('memory','cluster')),
  descendant_id TEXT NOT NULL,
  PRIMARY KEY(profile_id,ancestor_id,descendant_kind,descendant_id)
);
CREATE INDEX idx_cluster_dependency_source ON cluster_dependencies(profile_id,descendant_kind,descendant_id);
CREATE TABLE cluster_dirty (
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  reason TEXT NOT NULL,
  recorded_at TEXT NOT NULL,
  PRIMARY KEY(profile_id,cluster_id)
);
CREATE TABLE cluster_discovery_changes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  memory_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
  kind TEXT NOT NULL
);
CREATE INDEX idx_cluster_discovery_change_profile ON cluster_discovery_changes(profile_id,id);
CREATE TABLE cluster_discovery_state (
  profile_id TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
  last_event_id INTEGER NOT NULL
);
CREATE TABLE cluster_temporal_boundaries (
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  memory_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
  boundary_at TEXT NOT NULL,
  PRIMARY KEY(profile_id,memory_id,boundary_at)
);
CREATE INDEX idx_cluster_temporal_due ON cluster_temporal_boundaries(boundary_at);
CREATE TRIGGER cluster_discovery_memory_insert AFTER INSERT ON memories
WHEN new.type NOT IN ('cluster','task') BEGIN
  INSERT INTO cluster_discovery_changes(profile_id,memory_id,kind)
    VALUES(new.profile_id,new.id,'memory');
END;
CREATE TRIGGER cluster_discovery_assertion_insert AFTER INSERT ON assertion_revisions BEGIN
  INSERT INTO cluster_discovery_changes(profile_id,memory_id,kind)
    SELECT profile_id,new.memory_id,'assertion' FROM memories
    WHERE id=new.memory_id AND type NOT IN ('cluster','task');
END;
CREATE TRIGGER cluster_discovery_embedding_update AFTER UPDATE OF embedding ON memories
WHEN new.type NOT IN ('cluster','task') BEGIN
  INSERT INTO cluster_discovery_changes(profile_id,memory_id,kind)
    VALUES(new.profile_id,new.id,'embedding');
END;
CREATE TRIGGER cluster_discovery_edge_insert AFTER INSERT ON edges BEGIN
  INSERT INTO cluster_discovery_changes(profile_id,memory_id,kind)
    SELECT profile_id,new.memory_id,'graph' FROM memories
    WHERE id=new.memory_id AND type NOT IN ('cluster','task');
END;
CREATE TRIGGER cluster_revision_source_insert AFTER INSERT ON assertion_revisions BEGIN
  INSERT INTO cluster_dirty(profile_id,cluster_id,reason,recorded_at)
    SELECT d.profile_id,d.ancestor_id,'source_revision',CURRENT_TIMESTAMP
    FROM cluster_dependencies d WHERE d.descendant_kind='memory' AND d.descendant_id=new.memory_id
    ON CONFLICT(profile_id,cluster_id) DO UPDATE SET reason=excluded.reason,recorded_at=excluded.recorded_at;
END;
CREATE TRIGGER cluster_validity_source_insert AFTER INSERT ON validity_segments BEGIN
  INSERT OR IGNORE INTO cluster_temporal_boundaries(profile_id,memory_id,boundary_at)
    SELECT m.profile_id,m.id,new.valid_from FROM assertion_revisions ar
    JOIN memories m ON m.id=ar.memory_id
    WHERE ar.id=new.revision_id AND m.type NOT IN ('cluster','task')
    AND julianday(new.valid_from)>julianday('now');
  INSERT OR IGNORE INTO cluster_temporal_boundaries(profile_id,memory_id,boundary_at)
    SELECT m.profile_id,m.id,new.valid_to FROM assertion_revisions ar
    JOIN memories m ON m.id=ar.memory_id
    WHERE ar.id=new.revision_id AND new.valid_to IS NOT NULL
    AND julianday(new.valid_to)>julianday('now')
    AND m.type NOT IN ('cluster','task');
  INSERT INTO cluster_discovery_changes(profile_id,memory_id,kind)
    SELECT m.profile_id,m.id,'validity' FROM assertion_revisions ar
    JOIN memories m ON m.id=ar.memory_id
    WHERE ar.id=new.revision_id AND m.type NOT IN ('cluster','task');
  INSERT INTO cluster_dirty(profile_id,cluster_id,reason,recorded_at)
    SELECT d.profile_id,d.ancestor_id,'validity',CURRENT_TIMESTAMP
    FROM cluster_dependencies d JOIN assertion_revisions ar ON ar.memory_id=d.descendant_id
    WHERE d.descendant_kind='memory' AND ar.id=new.revision_id
    ON CONFLICT(profile_id,cluster_id) DO UPDATE SET reason=excluded.reason,recorded_at=excluded.recorded_at;
END;
CREATE TRIGGER cluster_validity_source_update AFTER UPDATE ON validity_segments BEGIN
  INSERT OR IGNORE INTO cluster_temporal_boundaries(profile_id,memory_id,boundary_at)
    SELECT m.profile_id,m.id,new.valid_from FROM assertion_revisions ar
    JOIN memories m ON m.id=ar.memory_id
    WHERE ar.id=new.revision_id AND m.type NOT IN ('cluster','task')
    AND julianday(new.valid_from)>julianday('now');
  INSERT OR IGNORE INTO cluster_temporal_boundaries(profile_id,memory_id,boundary_at)
    SELECT m.profile_id,m.id,new.valid_to FROM assertion_revisions ar
    JOIN memories m ON m.id=ar.memory_id
    WHERE ar.id=new.revision_id AND new.valid_to IS NOT NULL
    AND julianday(new.valid_to)>julianday('now')
    AND m.type NOT IN ('cluster','task');
  INSERT INTO cluster_discovery_changes(profile_id,memory_id,kind)
    SELECT m.profile_id,m.id,'validity' FROM assertion_revisions ar
    JOIN memories m ON m.id=ar.memory_id
    WHERE ar.id=new.revision_id AND m.type NOT IN ('cluster','task');
  INSERT INTO cluster_dirty(profile_id,cluster_id,reason,recorded_at)
    SELECT d.profile_id,d.ancestor_id,'validity',CURRENT_TIMESTAMP
    FROM cluster_dependencies d JOIN assertion_revisions ar ON ar.memory_id=d.descendant_id
    WHERE d.descendant_kind='memory' AND ar.id=new.revision_id
    ON CONFLICT(profile_id,cluster_id) DO UPDATE SET reason=excluded.reason,recorded_at=excluded.recorded_at;
END;
CREATE TRIGGER cluster_memory_forget AFTER UPDATE OF forgotten ON memories WHEN new.forgotten=1 AND old.forgotten=0 BEGIN
  DELETE FROM cluster_temporal_boundaries WHERE memory_id=new.id;
  INSERT INTO cluster_dirty(profile_id,cluster_id,reason,recorded_at)
    SELECT d.profile_id,d.ancestor_id,'forgotten',CURRENT_TIMESTAMP
    FROM cluster_dependencies d WHERE d.descendant_kind='memory' AND d.descendant_id=new.id
    ON CONFLICT(profile_id,cluster_id) DO UPDATE SET reason=excluded.reason,recorded_at=excluded.recorded_at;
END;

CREATE TABLE cluster_overrides (
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  child_kind TEXT NOT NULL CHECK(child_kind IN ('memory','cluster')),
  child_id TEXT NOT NULL,
  decision TEXT NOT NULL CHECK(decision IN ('pinned','excluded')),
  recorded_at TEXT NOT NULL,
  PRIMARY KEY(cluster_id, child_kind, child_id)
);
CREATE TABLE cluster_dissolved_groups (
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  fingerprint TEXT NOT NULL,
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  recorded_at TEXT NOT NULL,
  PRIMARY KEY(profile_id, fingerprint)
);

CREATE TABLE cluster_syntheses (
  id TEXT PRIMARY KEY,
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  memory_id TEXT NOT NULL UNIQUE REFERENCES memories(id),
  structure_revision INTEGER NOT NULL,
  body TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('published','stale','rejected')),
  recorded_at TEXT NOT NULL,
  recorded_sequence INTEGER NOT NULL,
  model_version TEXT,
  prompt_version TEXT,
  validation TEXT NOT NULL DEFAULT '{}',
  FOREIGN KEY(cluster_id, structure_revision) REFERENCES cluster_structure_revisions(cluster_id, revision)
);
CREATE INDEX idx_cluster_synthesis_latest ON cluster_syntheses(cluster_id, recorded_sequence DESC);
CREATE TRIGGER wiki_cluster_synthesis_insert AFTER INSERT ON cluster_syntheses BEGIN
  INSERT INTO wiki_changes(profile_id,memory_id,kind)
    SELECT profile_id,new.memory_id,'cluster_synthesis' FROM clusters WHERE id=new.cluster_id;
END;
CREATE TABLE cluster_synthesis_children (
  synthesis_id TEXT NOT NULL REFERENCES cluster_syntheses(id) ON DELETE CASCADE,
  child_cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  child_synthesis_id TEXT NOT NULL REFERENCES cluster_syntheses(id),
  PRIMARY KEY(synthesis_id, child_cluster_id)
);
CREATE TABLE cluster_claims (
  id TEXT PRIMARY KEY,
  synthesis_id TEXT NOT NULL REFERENCES cluster_syntheses(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  text TEXT NOT NULL,
  embedding BLOB,
  UNIQUE(synthesis_id, position)
);
CREATE VIRTUAL TABLE cluster_claims_fts USING fts5(text, content='cluster_claims', content_rowid='rowid', tokenize='porter');
CREATE TRIGGER cluster_claims_ai AFTER INSERT ON cluster_claims BEGIN
  INSERT INTO cluster_claims_fts(rowid,text) VALUES(new.rowid,new.text);
END;
CREATE TRIGGER cluster_claims_ad AFTER DELETE ON cluster_claims BEGIN
  INSERT INTO cluster_claims_fts(cluster_claims_fts,rowid,text) VALUES('delete',old.rowid,old.text);
END;
CREATE TABLE cluster_claim_evidence (
  claim_id TEXT NOT NULL REFERENCES cluster_claims(id) ON DELETE CASCADE,
  memory_id TEXT NOT NULL REFERENCES memories(id),
  assertion_revision_id TEXT NOT NULL REFERENCES assertion_revisions(id),
  covered INTEGER NOT NULL CHECK(covered IN (0,1)),
  PRIMARY KEY(claim_id, assertion_revision_id)
);
CREATE INDEX idx_cluster_evidence_source ON cluster_claim_evidence(memory_id);

CREATE TABLE cluster_runs (
  id TEXT PRIMARY KEY,
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  state TEXT NOT NULL CHECK(state IN ('queued','running','pausing','paused','waiting','completed','completed_with_errors','cancelled','failed')),
  trigger_name TEXT NOT NULL,
  checkpoint TEXT,
  lease_owner TEXT,
  lease_until TEXT,
  snapshot_as_of TEXT NOT NULL,
  snapshot_known_as_of TEXT NOT NULL,
  snapshot_sequence INTEGER NOT NULL,
  event_after INTEGER NOT NULL,
  event_through INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE TABLE cluster_schedules (
  profile_id TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
  enabled INTEGER NOT NULL DEFAULT 0,
  local_time TEXT NOT NULL DEFAULT '02:00',
  timezone TEXT NOT NULL,
  last_local_date TEXT
);
CREATE UNIQUE INDEX idx_cluster_one_unfinished ON cluster_runs(profile_id)
  WHERE state IN ('queued','running','pausing','paused','waiting');
CREATE TABLE cluster_work_items (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES cluster_runs(id) ON DELETE CASCADE,
  subject_kind TEXT NOT NULL,
  subject_id TEXT NOT NULL,
  stage TEXT NOT NULL,
  status TEXT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  input_fingerprint TEXT,
  output TEXT,
  last_error TEXT,
  UNIQUE(run_id, subject_kind, subject_id, stage)
);
CREATE TABLE cluster_audit_events (
  id TEXT PRIMARY KEY,
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  cluster_id TEXT REFERENCES clusters(id) ON DELETE CASCADE,
  run_id TEXT REFERENCES cluster_runs(id) ON DELETE SET NULL,
  actor TEXT NOT NULL,
  trigger_name TEXT NOT NULL,
  action TEXT NOT NULL,
  detail TEXT NOT NULL DEFAULT '{}',
  recorded_at TEXT NOT NULL
);
CREATE INDEX idx_cluster_audit_profile ON cluster_audit_events(profile_id, recorded_at);
