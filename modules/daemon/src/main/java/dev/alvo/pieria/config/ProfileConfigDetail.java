package dev.alvo.pieria.config;

import dev.alvo.pieria.config.model.DaemonOverrides;

/**
 * The three configuration layers for one profile, as the console needs them in a single read.
 *
 * @param global    the global baseline, fully populated
 * @param overrides only what this profile actually sets — sparse, nulls omitted on the wire
 * @param effective global overlaid with overrides, fully populated
 */
public record ProfileConfigDetail(
  DaemonOverrides global,
  DaemonOverrides overrides,
  DaemonOverrides effective) {
}
