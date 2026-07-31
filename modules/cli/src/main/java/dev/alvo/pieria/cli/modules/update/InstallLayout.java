package dev.alvo.pieria.cli.modules.update;

import dev.alvo.pieria.tools.os.InstallHome;
import dev.alvo.pieria.tools.os.OsFamily;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

  InstallLayout(Path binDir) {
    this.binDir = binDir;
  }

  public static InstallLayout resolve(UnaryOperator<String> env, Path userHome, Platform platform,
                                      Path explicitInstallRoot) {
    Path binDir = resolveBinDir(env, userHome, platform, explicitInstallRoot);
    return new InstallLayout(binDir);
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
    return InstallHome.defaultHome(env, userHome, OsFamily.detect() == OsFamily.WINDOWS);
  }

  public Path binDir() {
    return binDir;
  }
}
