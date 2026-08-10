package dev.alvo.pieria.cli.modules.harness;

import dev.alvo.pieria.tools.os.InstallHome;
import dev.alvo.pieria.tools.os.OsFamily;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the Pieria install root ({@code PIERIA_HOME}) and the {@code pieria-gateway}/{@code pieria}
 * executables. Resolution mirrors the packaging conventions in {@code packaging/install.sh}:
 *
 * <ol>
 *   <li>{@code PIERIA_HOME} environment variable, if set;</li>
 *   <li>the parent of the directory holding the running executable
 *       (so {@code <home>/bin/pieria} resolves {@code <home>});</li>
 *   <li>the OS default: {@code ~/.local/share/pieria} (Unix) or {@code %LOCALAPPDATA%\Pieria} (Windows).</li>
 * </ol>
 */
public final class PathResolver {

  private final java.util.function.Function<String, String> env;
  private final Path selfExecutable;
  private final Path userHome;
  private final boolean windows;

  public PathResolver(java.util.function.Function<String, String> env,
                      Path selfExecutable,
                      Path userHome,
                      boolean windows) {
    this.env = env;
    this.selfExecutable = selfExecutable;
    this.userHome = userHome;
    this.windows = windows;
  }

  /**
   * Production factory: real env, current process command, system user home and OS.
   */
  public static PathResolver create() {
    Path self = ProcessHandle.current().info().command().map(Path::of).orElse(null);
    boolean win = OsFamily.detect() == OsFamily.WINDOWS;
    return new PathResolver(System::getenv, self, Path.of(System.getProperty("user.home")), win);
  }

  /**
   * The Pieria install root.
   */
  public Path pieriaHome() {
    String fromEnv = env.apply("PIERIA_HOME");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return Path.of(fromEnv.strip());
    }
    // <home>/bin/<exe>  ->  <home>
    if (selfExecutable != null) {
      Path binDir = selfExecutable.getParent();
      if (binDir != null && binDir.getFileName() != null
        && "bin".equals(binDir.getFileName().toString()) && binDir.getParent() != null) {
        return binDir.getParent();
      }
    }
    return InstallHome.defaultHome(env, userHome, windows);
  }

  /**
   * Absolute path to the {@code pieria-gateway} executable for the MCP {@code command}.
   */
  public String gatewayCommand() {
    return resolveExecutable(windows ? "pieria-gateway.exe" : "pieria-gateway");
  }

  /**
   * Absolute path to the {@code pieria} CLI executable — the command a harness invokes for hooks.
   * Mirrors {@link #gatewayCommand()}.
   */
  public String cliCommand() {
    return resolveExecutable(windows ? "pieria.exe" : "pieria");
  }

  /**
   * Resolves an absolute path to the named executable, preferring one sitting next to the running
   * executable and falling back to {@code <PIERIA_HOME>/bin/<exeName>}.
   */
  private String resolveExecutable(String exeName) {
    if (selfExecutable != null) {
      Path binDir = selfExecutable.getParent();
      if (binDir != null) {
        Path sibling = binDir.resolve(exeName);
        if (Files.isRegularFile(sibling)) {
          return sibling.toString();
        }
      }
    }
    return pieriaHome().resolve("bin").resolve(exeName).toString();
  }

  public Path userHome() {
    return userHome;
  }
}
