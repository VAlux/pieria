-- Per-profile real inference token spend, accumulated at event time, one row per model tier.
-- Complements profile_usage (which tracks heuristic token *savings*); this is what Pieria actually
-- *spent* on the provider. Tokens are the real prompt/completion counts the provider reports, not
-- the chars/4 heuristic. Embedding usage may stay zero for providers (e.g. Ollama) that do not
-- report it over the OpenAI-compatible API.
CREATE TABLE profile_inference_usage (
  profile_id        TEXT    NOT NULL REFERENCES profiles (id),
  tier              TEXT    NOT NULL,           -- EXTRACTION | SYNTHESIS | EMBEDDING
  calls             INTEGER NOT NULL DEFAULT 0, -- number of model calls recorded for the tier
  prompt_tokens     INTEGER NOT NULL DEFAULT 0, -- provider-reported input tokens
  completion_tokens INTEGER NOT NULL DEFAULT 0, -- provider-reported output tokens
  updated_at        TEXT    NOT NULL,           -- ISO-8601 instant of the last accumulation
  PRIMARY KEY (profile_id, tier)
);
