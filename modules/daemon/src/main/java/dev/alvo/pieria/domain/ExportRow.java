package dev.alvo.pieria.domain;

/**
 * One line of an NDJSON export. Wraps a memory plus the profile name for provenance;
 * this shape enables local-to-server migration.
 */
public record ExportRow(String profileName, Memory memory) {
}
