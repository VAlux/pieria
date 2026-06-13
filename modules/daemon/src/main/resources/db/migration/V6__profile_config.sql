-- Per-profile configuration overrides, pushed by the CLI from a project's .pieria/config.toml
-- (PUT /v1/profiles/{name}/config) and merged onto the global PieriaProperties at request time
-- by EffectiveConfigResolver. One row per profile holding the whitelisted overrides as canonical
-- kebab-case JSON (TOML is a client-side authoring format only; the daemon never sees it).

CREATE TABLE profile_config (
  profile_id  TEXT PRIMARY KEY REFERENCES profiles (id),
  config_json TEXT NOT NULL,
  updated_at  TEXT NOT NULL              -- ISO-8601
);
