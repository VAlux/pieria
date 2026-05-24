package dev.alvo.pieria.domain;

/**
 * One line of an NDJSON export (SPEC 13). Wraps a memory plus the profile name for provenance;
 * this shape becomes the local->server migration format.
 */
public record ExportRow(String profileName, Memory memory) {
}
