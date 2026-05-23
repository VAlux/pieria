-- Phase 2 (SPEC 5.1, 6.7): store the raw embedding vector on the memory row.
-- The float[] is serialized as little-endian float32 bytes (4 bytes per dimension).
-- completeVectorization writes this column; supersession clears it (NULL) for the old row.
-- The sqlite-vec virtual table that indexes these vectors is deferred to Phase 3.

ALTER TABLE memories
  ADD COLUMN embedding BLOB;
