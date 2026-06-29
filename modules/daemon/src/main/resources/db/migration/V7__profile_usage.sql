-- Per-profile lifetime token-savings counters ("Pieria impact"). One row per profile, accumulated
-- at event time (recall/ingest) rather than recomputed on read, so the totals are a true lifetime
-- odometer that survives supersession and any future message pruning. All token figures are the
-- shared chars/4 heuristic (Tokens util) — a relative estimate, not billing-grade accounting.

CREATE TABLE profile_usage (
  profile_id            TEXT PRIMARY KEY REFERENCES profiles (id),
  recall_count          INTEGER NOT NULL DEFAULT 0,
  ingest_count          INTEGER NOT NULL DEFAULT 0,
  tokens_saved_evidence INTEGER NOT NULL DEFAULT 0, -- headline: evidence tokens - answer tokens
  tokens_saved_naive    INTEGER NOT NULL DEFAULT 0, -- upper bound: active corpus - answer tokens
  tokens_recall_served  INTEGER NOT NULL DEFAULT 0, -- synthesized answer tokens (informational)
  tokens_ingested       INTEGER NOT NULL DEFAULT 0, -- raw messages distilled on ingest
  tokens_stored         INTEGER NOT NULL DEFAULT 0, -- memories produced from those messages
  updated_at            TEXT    NOT NULL            -- ISO-8601
);
