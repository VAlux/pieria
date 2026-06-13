package dev.alvo.pieria.cli.modules.update;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uses an already-built distribution directory ({@code --from <dir>}, or the repo's
 * {@code modules/daemon/build/distributions/pieria-native} for {@code --from-build}). Validates the
 * expected artifacts are present, then hands the directory straight to the swapper — no download.
 */
public final class LocalDistSource implements BinarySource {

  private final Path distDir;
  private final boolean jar;
  private final Platform platform;

  public LocalDistSource(Path distDir, boolean jar, Platform platform) {
    this.distDir = distDir.toAbsolutePath().normalize();
    this.jar = jar;
    this.platform = platform;
  }

  @Override
  public StagedDist resolve() {
    if (!Files.isDirectory(distDir)) {
      throw new UpdateException("distribution directory not found: " + distDir
        + "\nBuild it first with `./gradlew " + (jar ? "jvmDist" : "nativeDist") + "`.");
    }
    StagedDist dist = new StagedDist(distDir, jar);
    if (jar) {
      requireFile(dist.libDir().resolve("pieria.jar"));
      requireFile(dist.libDir().resolve("pieria-gateway.jar"));
      requireFile(dist.libDir().resolve("pieria-cli.jar"));
    } else {
      for (String name : BINARIES) {
        requireFile(dist.binDir().resolve(platform.exeName(name)));
      }
    }
    return dist;
  }

  @Override
  public String describe() {
    return "local " + (jar ? "JVM" : "native") + " distribution at " + distDir;
  }

  private void requireFile(Path file) {
    if (!Files.isRegularFile(file)) {
      throw new UpdateException("malformed distribution at " + distDir + ": missing " + distDir.relativize(file));
    }
  }
}
