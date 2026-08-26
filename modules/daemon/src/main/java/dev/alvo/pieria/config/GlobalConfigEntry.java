package dev.alvo.pieria.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One process-global configuration key as the console renders it.
 *
 * <p>{@code value} is what the RUNNING daemon is using; {@code fileValue} is what
 * {@code pieria.properties} holds. For a restart-tier key these differ between a save and the next
 * restart, and {@code restartPending} carries that fact — so the console's banner survives a page
 * reload instead of living only in the session that made the edit.
 *
 * @param provenance     {@code set} when the key is assigned in the config-dir properties file,
 *                       {@code default} when the shipped value applies
 * @param restartPending the file and the running process disagree
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GlobalConfigEntry(
  String key,
  String section,
  String tier,
  String kind,
  List<String> options,
  String label,
  String hint,
  String value,
  String fileValue,
  String provenance,
  boolean restartPending) {

  public GlobalConfigEntry {
    options = options == null ? List.of() : List.copyOf(options);
  }
}
