package dev.alvo.pieria.cli.modules.update;

import java.nio.file.Path;

/**
 * A resolved distribution staged on disk and ready to install from. Mirrors the layout produced by
 * the {@code nativeDist}/{@code jvmDist} Gradle tasks and the release tarball: native binaries (and
 * a {@code version.txt}) under {@code bin/}; for a JVM distribution, runnable jars under {@code lib/}.
 *
 * @param root the staging directory containing {@code bin/} (and {@code lib/} when {@code jar})
 * @param jar  {@code true} for a JVM distribution (jars in {@code lib/}); {@code false} for native
 */
public record StagedDist(Path root, boolean jar) {

  public Path binDir() {
    return root.resolve("bin");
  }

  public Path libDir() {
    return root.resolve("lib");
  }
}
