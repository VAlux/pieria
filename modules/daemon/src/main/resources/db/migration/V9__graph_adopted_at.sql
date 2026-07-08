-- Orphan adoption ("reminiscence") marker. NULL = the memory has never been through graph adoption;
-- a non-null ISO-8601 timestamp = it has been scanned, so it is never re-extracted even if it
-- legitimately yielded no entities/edges. See ReminiscenceService.
ALTER TABLE memories ADD COLUMN graph_adopted_at TEXT;

-- Supports the orphan finder's active/unadopted scan (SqliteMemoryStore.findGraphOrphans).
CREATE INDEX idx_mem_graph_adopted ON memories (profile_id, created_at)
  WHERE superseded = 0 AND graph_adopted_at IS NULL;
