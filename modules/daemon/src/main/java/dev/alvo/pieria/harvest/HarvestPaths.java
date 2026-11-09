package dev.alvo.pieria.harvest;

import java.nio.file.Path;

/**
 * Where harvesters live. This is under the app-data root rather than the install directory, because
 * an update replaces the install directory and would take installed harvesters and secrets with it.
 */
public final class HarvestPaths {

  private final Path root;
  private final Path bundledBin;

  /** @param bundledBin the install's {@code bin} directory, which holds the bundled harvesters' binary */
  public HarvestPaths(Path root, Path bundledBin) {
    this.root = root;
    this.bundledBin = bundledBin;
  }

  public Path bundledBin() {
    return bundledBin;
  }

  public Path root() {
    return root;
  }

  public Path harvesterDir(String id) {
    return root.resolve(id);
  }

  public Path pinsFile() {
    return root.resolve("pins.json");
  }

  public Path secretsFile() {
    return root.resolve("secrets.json");
  }
}
