-- Immutable content remains in memories; occurrences and revisions have independent identities.
ALTER TABLE memories ADD COLUMN forgotten INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN recorded_at TEXT;
ALTER TABLE messages ADD COLUMN recorded_sequence INTEGER NOT NULL DEFAULT 0;
UPDATE messages SET recorded_at = strftime('%Y-%m-%dT%H:%M:%f', created_at) || '000000Z';
ALTER TABLE edges ADD COLUMN recorded_at TEXT;
ALTER TABLE edges ADD COLUMN recorded_sequence INTEGER NOT NULL DEFAULT 0;
UPDATE edges SET recorded_at = strftime('%Y-%m-%dT%H:%M:%f', created_at) || '000000Z';

CREATE TABLE temporal_transactions (
  sequence INTEGER PRIMARY KEY AUTOINCREMENT,
  recorded_at TEXT NOT NULL
);
CREATE TABLE memory_assertions (
  id TEXT PRIMARY KEY,
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  source_occurrence TEXT NOT NULL,
  fact_group TEXT NOT NULL,
  approximate INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_assertion_group ON memory_assertions(profile_id, fact_group);
CREATE TABLE assertion_revisions (
  id TEXT PRIMARY KEY,
  assertion_id TEXT NOT NULL REFERENCES memory_assertions(id) ON DELETE CASCADE,
  memory_id TEXT NOT NULL REFERENCES memories(id) ON DELETE CASCADE,
  valid_from TEXT NOT NULL,
  valid_to TEXT,
  stated_at TEXT,
  recorded_from TEXT NOT NULL,
  recorded_to TEXT,
  from_sequence INTEGER NOT NULL,
  to_sequence INTEGER,
  corrects TEXT,
  source_payload TEXT NOT NULL DEFAULT '{}',
  conflict_evidence TEXT,
  invalidated INTEGER NOT NULL DEFAULT 0,
  CHECK(valid_to IS NULL OR valid_from < valid_to)
);
CREATE UNIQUE INDEX idx_revision_current ON assertion_revisions(assertion_id) WHERE to_sequence IS NULL;
CREATE INDEX idx_revision_memory ON assertion_revisions(memory_id);
CREATE TABLE validity_segments (
  revision_id TEXT NOT NULL REFERENCES assertion_revisions(id) ON DELETE CASCADE,
  profile_id TEXT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  fact_group TEXT NOT NULL,
  valid_from TEXT NOT NULL,
  valid_to TEXT,
  recorded_from TEXT NOT NULL,
  recorded_to TEXT,
  from_sequence INTEGER NOT NULL,
  to_sequence INTEGER
);
CREATE INDEX idx_segment_visibility ON validity_segments(profile_id, valid_from, valid_to, recorded_from, recorded_to);
CREATE INDEX idx_segment_group ON validity_segments(profile_id, fact_group, to_sequence);
CREATE INDEX idx_segment_revision ON validity_segments(revision_id);
CREATE TABLE temporal_coverage (
  profile_id TEXT PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
  limitation TEXT NOT NULL
);
-- A superseded row without a surviving successor might have been forgotten. Never resurrect it.
UPDATE memories SET forgotten = 1 WHERE superseded = 1 AND NOT EXISTS (
  SELECT 1 FROM memories successor WHERE successor.profile_id = memories.profile_id
  AND successor.supersedes = memories.id
);
INSERT INTO temporal_coverage SELECT DISTINCT profile_id,
  'Legacy history is approximate; unlinked superseded rows are excluded because forgetting cannot be distinguished from supersession.'
  FROM memories;
-- V2's live snapshot is grounded in the actual live set, not inferred chain heads.
INSERT INTO temporal_transactions(recorded_at) VALUES(strftime('%Y-%m-%dT%H:%M:%f', 'now') || '000000Z');
INSERT INTO memory_assertions(id, profile_id, source_occurrence, fact_group, approximate)
SELECT 'legacy:' || id, profile_id, 'legacy:' || id,
  CASE WHEN (type IN ('fact','instruction') OR topic_key LIKE 'trace:outcome:%') AND topic_key IS NOT NULL
    THEN type || ':' || topic_key ELSE 'memory:' || id END, 1
FROM memories WHERE forgotten = 0;
-- Preserve inferred original intervals; resolution segments carry the observed replacements.
INSERT INTO assertion_revisions(id, assertion_id, memory_id, valid_from, stated_at,
  recorded_from, from_sequence, source_payload)
SELECT 'legacy:' || m.id, 'legacy:' || m.id, m.id,
  COALESCE(strftime('%Y-%m-%dT%H:%M:%f', CASE WHEN json_valid(m.payload)
    THEN json_extract(m.payload, '$.stated_at') END), strftime('%Y-%m-%dT%H:%M:%f', m.created_at)) || '000000Z',
  strftime('%Y-%m-%dT%H:%M:%f', CASE WHEN json_valid(m.payload)
    THEN json_extract(m.payload, '$.stated_at') END) || '000000Z',
  strftime('%Y-%m-%dT%H:%M:%f', m.created_at) || '000000Z', 0, m.payload
FROM memories m WHERE forgotten = 0;
-- Before a surviving successor was stored, the predecessor was believed open-ended.
INSERT INTO validity_segments(revision_id, profile_id, fact_group, valid_from, valid_to,
  recorded_from, recorded_to, from_sequence, to_sequence)
SELECT r.id, a.profile_id, a.fact_group, r.valid_from, NULL, r.recorded_from,
  CASE WHEN m.superseded = 0 THEN NULL ELSE
    (SELECT MIN(sr.recorded_from) FROM memories successor JOIN assertion_revisions sr ON sr.memory_id = successor.id
      WHERE successor.supersedes = m.id AND successor.profile_id = m.profile_id) END, 0,
  CASE WHEN m.superseded = 0 THEN NULL ELSE 0 END
FROM assertion_revisions r JOIN memory_assertions a ON a.id = r.assertion_id
JOIN memories m ON m.id = r.memory_id
WHERE m.superseded = 0 OR EXISTS (
  SELECT 1 FROM memories successor JOIN assertion_revisions sr ON sr.memory_id = successor.id
  WHERE successor.supersedes = m.id AND successor.profile_id = m.profile_id);
-- After learning the successor, preserve the predecessor only in its recoverable past window.
INSERT INTO validity_segments(revision_id, profile_id, fact_group, valid_from, valid_to,
  recorded_from, from_sequence)
SELECT r.id, a.profile_id, a.fact_group, r.valid_from,
  MIN(sr.valid_from), MIN(sr.recorded_from), 0
FROM assertion_revisions r JOIN memory_assertions a ON a.id = r.assertion_id
JOIN memories m ON m.id = r.memory_id JOIN memories successor ON successor.supersedes = m.id
  AND successor.profile_id = m.profile_id
JOIN assertion_revisions sr ON sr.memory_id = successor.id
WHERE m.superseded = 1
GROUP BY r.id HAVING r.valid_from < MIN(sr.valid_from);
-- An ancestor of an unrecoverable terminal row has no current visible interval to restore.
UPDATE assertion_revisions SET invalidated = 1 WHERE id IN (
  SELECT r.id FROM assertion_revisions r JOIN memories m ON m.id = r.memory_id
  WHERE m.superseded = 1 AND NOT EXISTS (
    SELECT 1 FROM validity_segments s WHERE s.revision_id = r.id AND s.to_sequence IS NULL
  )
);
INSERT OR IGNORE INTO vectorization_outbox(memory_id, enqueued_at, attempts)
SELECT id, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), 0 FROM memories
WHERE forgotten = 0 AND type != 'task' AND embedding IS NULL;
