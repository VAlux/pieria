package dev.alvo.pieria.config;

/**
 * The effective configuration for one profile: the global {@link PieriaProperties} values with the
 * profile's stored overrides applied. Reuses the {@code PieriaProperties} nested records so
 * consumers are drop-in; the process-global fields of {@code Ingestion} (outbox/vectorization)
 * always carry the global values — only the request-time tuning is per-profile.
 */
public record ResolvedConfig(
  PieriaProperties.Ingestion ingestion,
  PieriaProperties.Retrieval retrieval) {
}
