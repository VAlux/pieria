package dev.alvo.pieria.cli.modules.update;

import java.nio.file.Path;

/**
 * A resolved native distribution staged on disk and ready to install from. Mirrors the layout
 * produced by the {@code nativeDist} Gradle task and the release archive: native binaries (and a
 * {@code version.txt}) under {@code bin/}, with browser assets under {@code console/}.
 *
 * @param root the staging directory containing {@code bin/}
 */
public record StagedDist(Path root) {

  public Path binDir() {
    return root.resolve("bin");
  }

  public Path consoleDir() {
    return root.resolve("console");
  }
}
