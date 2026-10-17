-- Wiki projection pages through one profile by memory ID. Without this composite index,
-- SQLite scans/sorts the whole profile and rechecks temporal visibility on every batch.
CREATE INDEX idx_mem_profile_id ON memories (profile_id, id);
