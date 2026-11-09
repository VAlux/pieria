package dev.alvo.pieria.harvest;

import java.nio.file.Path;

/**
 * Where harvesters live. This is under the app-data root rather than the install directory, because
 * an update replaces the install directory and would take installed harvesters and secrets with it.
 */
public final class HarvestPaths {

  private final Path root;

  public HarvestPaths(Path root) {
    this.root = root;
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
