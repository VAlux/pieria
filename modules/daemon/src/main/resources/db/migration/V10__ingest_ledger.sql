-- Incremental-onboarding ledger: one row per processed content document, recording the hash of
-- the pipeline-relevant inputs (pipeline version, extraction samples, provenance, text). A
-- matching hash on re-onboard skips every model call for that document; rows are written only
-- after the document's memories are durably stored, so an interrupted onboard resumes where it
-- left off. Rows for since-deleted documents are harmless leftovers (memories are superseded,
-- never bulk-deleted).

CREATE TABLE ingest_ledger (
  profile_id   TEXT NOT NULL REFERENCES profiles (id),
  scope        TEXT NOT NULL, -- source type: markdown | text | pdf | web
  item_key     TEXT NOT NULL, -- document provenance (repo-relative path or URL)
  content_hash TEXT NOT NULL, -- Hash.hash128 over pipeline version + samples + provenance + text
  processed_at TEXT NOT NULL, -- ISO-8601
  PRIMARY KEY (profile_id, scope, item_key)
);
