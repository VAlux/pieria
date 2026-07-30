package dev.alvo.pieria.cli.modules.harness;

import dev.alvo.pieria.tools.os.InstallHome;
import dev.alvo.pieria.tools.os.OsFamily;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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
  private final Optional<Path> selfExecutable;
  private final Path userHome;
  private final boolean windows;

  public PathResolver(java.util.function.Function<String, String> env,
                      Optional<Path> selfExecutable,
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
    Optional<Path> self = ProcessHandle.current().info().command().map(Path::of);
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
    if (selfExecutable.isPresent()) {
      Path exe = selfExecutable.get();
      Path binDir = exe.getParent();
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
    String exeName = windows ? "pieria-gateway.exe" : "pieria-gateway";
    // Prefer a gateway sitting next to the running executable.
    if (selfExecutable.isPresent()) {
      Path binDir = selfExecutable.get().getParent();
      if (binDir != null) {
        Path sibling = binDir.resolve(exeName);
        if (Files.isRegularFile(sibling)) {
          return sibling.toString();
        }
      }
    }
    return pieriaHome().resolve("bin").resolve(exeName).toString();
  }

  /**
   * Absolute path to the {@code pieria} CLI executable — the command a harness invokes for hooks.
   * Mirrors {@link #gatewayCommand()}.
   */
  public String cliCommand() {
    String exeName = windows ? "pieria.exe" : "pieria";
    if (selfExecutable.isPresent()) {
      Path binDir = selfExecutable.get().getParent();
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
