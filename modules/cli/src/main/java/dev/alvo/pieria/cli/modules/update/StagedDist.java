package dev.alvo.pieria.cli.modules.update;

import java.nio.file.Path;

/**
 * A resolved native distribution staged on disk and ready to install from. Mirrors the layout
 * produced by the {@code nativeDist} Gradle task and the release tarball: native binaries (and a
 * {@code version.txt}) under {@code bin/}.
 *
 * @param root the staging directory containing {@code bin/}
 */
public record StagedDist(Path root) {

  public Path binDir() {
    return root.resolve("bin");
  }
}
