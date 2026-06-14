package dev.alvo.pieria.cli.modules.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Resolves where an installed Pieria actually lives, so {@code update} writes over the real files
 * rather than symlinks or the build tree.
 *
 * <p>The key subtlety: the installer links {@code ~/.local/bin/{pieria,...}} as symlinks into
 * {@code ~/.local/share/pieria/bin}. We must target the symlink <em>targets</em>. We also cannot use
 * the running executable's path — under {@code --from-build} the running {@code pieria} is the
 * freshly built one in the dist tree, not the install. So the bin dir is resolved by following the
 * installed {@code ~/.local/bin/pieria} symlink, which points at the true install regardless of a
 * custom {@code PIERIA_HOME}.
 */
public final class InstallLayout {

  private final Path binDir;
  private final List<Path> harnessCandidates;

  InstallLayout(Path binDir, List<Path> harnessCandidates) {
    this.binDir = binDir;
    this.harnessCandidates = harnessCandidates;
  }

  public static InstallLayout resolve(UnaryOperator<String> env, Path userHome, Platform platform,
                                      Path explicitInstallRoot) {
    Path binDir = resolveBinDir(env, userHome, platform, explicitInstallRoot);
    Path home = binDir.getParent() == null ? binDir : binDir.getParent();

    List<Path> harness = new ArrayList<>();
    harness.add(home.resolve("harness")); // canonical: <PIERIA_HOME>/harness
    // Symlink quirk: when binaries are linked into a dir literally named "bin" (e.g. ~/.local/bin),
    // the harness wiring resolved PIERIA_HOME to that dir's parent, so scripts live at ~/.local/harness.
    Path linkDir = binSymlinkDir(env, userHome);
    if (linkDir.getFileName() != null && "bin".equals(linkDir.getFileName().toString())
      && linkDir.getParent() != null) {
      harness.add(linkDir.getParent().resolve("harness"));
    }
    return new InstallLayout(binDir, harness.stream().distinct().toList());
  }

  private static Path resolveBinDir(UnaryOperator<String> env, Path userHome, Platform platform,
                                    Path explicitInstallRoot) {
    if (explicitInstallRoot != null) {
      return explicitInstallRoot.resolve("bin");
    }
    String pieriaHome = env.apply("PIERIA_HOME");
    if (pieriaHome != null && !pieriaHome.isBlank()) {
      return Path.of(pieriaHome.strip()).resolve("bin");
    }
    // Follow the installed `pieria` symlink to its real location — robust to a custom PIERIA_HOME.
    Path link = binSymlinkDir(env, userHome).resolve(platform.exeName("pieria"));
    if (Files.exists(link)) {
      try {
        Path parent = link.toRealPath().getParent();
        if (parent != null) {
          return parent;
        }
      } catch (IOException ignored) {
        // fall through to the OS default
      }
    }
    return defaultHome(env, userHome).resolve("bin");
  }

  private static Path binSymlinkDir(UnaryOperator<String> env, Path userHome) {
    String binDir = env.apply("PIERIA_BIN_DIR");
    if (binDir != null && !binDir.isBlank()) {
      return Path.of(binDir.strip());
    }
    return userHome.resolve(".local").resolve("bin");
  }

  private static Path defaultHome(UnaryOperator<String> env, Path userHome) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("win")) {
      String localAppData = env.apply("LOCALAPPDATA");
      Path base = (localAppData != null && !localAppData.isBlank())
        ? Path.of(localAppData) : userHome.resolve("AppData").resolve("Local");
      return base.resolve("Pieria");
    }
    return userHome.resolve(".local").resolve("share").resolve("pieria");
  }

  public Path binDir() {
    return binDir;
  }

  /**
   * Harness dirs that actually exist on disk (a harness was wired). Hook-script refresh targets
   * these; if empty, refresh is skipped.
   */
  public List<Path> existingHarnessDirs() {
    return harnessCandidates.stream().filter(Files::isDirectory).toList();
  }
}
