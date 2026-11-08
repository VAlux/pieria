-- Exposes payload.origin.source_id (set on memories harvested from an external source) to SQL.
-- A generated column keeps payload the single source of truth and needs no write-path change.
-- payload is an opaque string for explicit writes, so json_valid guards json_extract, which would
-- otherwise raise on any row holding non-JSON text.
ALTER TABLE memories ADD COLUMN origin_source_id TEXT
  GENERATED ALWAYS AS (CASE WHEN json_valid(payload) THEN json_extract(payload, '$.origin.source_id') END) VIRTUAL;

CREATE INDEX idx_mem_profile_origin ON memories (profile_id, origin_source_id)
  WHERE origin_source_id IS NOT NULL;
