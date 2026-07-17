package dev.alvo.pieria.ingestion;

/**
 * Controls whether graph extraction is part of the ingest's critical path.
 */
public enum GraphMode {
  SYNCHRONOUS,
  DEFERRED
}
