-- Phase 8: entity-relation graph over the memory store.
--
-- entities and edges are profile-scoped with content-addressed ids (see ContentId.forEntity /
-- forEdge), so re-ingest is idempotent via INSERT OR IGNORE. An edge is "active" only while its
-- source memory is active (memories.superseded = 0); edges are never physically deleted on
-- supersession, so all graph queries join memories and filter superseded = 0 at read time.
--
-- The Postgres server backend (Phase 6) mirrors this logical model (pgvector-adjacent schema with
-- recursive neighborhood queries); its dialect-specific migration is deferred to that phase.

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
