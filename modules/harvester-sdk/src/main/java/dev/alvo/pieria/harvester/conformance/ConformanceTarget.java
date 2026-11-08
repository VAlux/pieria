package dev.alvo.pieria.harvester.conformance;

import dev.alvo.pieria.harvester.manifest.HarvesterManifest;
import dev.alvo.pieria.harvester.manifest.ManifestParser;

import java.nio.file.Path;
import java.util.List;

/** The harvester under test and how to launch it. */
public record ConformanceTarget(HarvesterManifest manifest, List<String> command, Path workingDir) {

  public ConformanceTarget {
    command = List.copyOf(command);
  }

  /** The target an installed harvester would be, from its {@code harvester.toml}. */
  public static ConformanceTarget fromManifest(Path manifestFile) {
    HarvesterManifest manifest = ManifestParser.load(manifestFile);
    Path dir = manifestFile.toAbsolutePath().getParent();
    return new ConformanceTarget(manifest, manifest.resolvedCommand(dir), dir);
  }
}
