package dev.alvo.pieria.harvest;

import dev.alvo.pieria.harvester.manifest.HarvesterManifest;

import java.nio.file.Path;
import java.util.List;

/**
 * A harvester ready to run.
 *
 * @param directory where its command resolves from: its install directory, or the install's
 *                  {@code bin} for a bundled harvester
 * @param bundled   whether it ships with Pieria, rather than being installed and pinned
 */
public record RegisteredHarvester(HarvesterManifest manifest, Path directory, boolean bundled) {

  public List<String> command() {
    return manifest.resolvedCommand(directory);
  }
}
