package dev.alvo.pieria.harvest;

import dev.alvo.pieria.harvester.manifest.HarvesterManifest;

import java.nio.file.Path;
import java.util.List;

/** A harvester that is installed and matches its pin. */
public record RegisteredHarvester(HarvesterManifest manifest, Path directory) {

  public List<String> command() {
    return manifest.resolvedCommand(directory);
  }
}
