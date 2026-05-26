package dev.alvo.pieria.cli.modules.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Starts and stops the daemon process. Hybrid: prefers an installed OS service manager (launchd on
 * macOS, systemd --user on Linux) and falls back to spawning a detached daemon process tracked by a
 * PID file when no service is registered.
 *
 * <p>This class only returns outcomes; the commands own user-facing output.
 */
public final class DaemonProcess {

  private static final String LAUNCHD_LABEL = "dev.alvo.pieria.daemon";
  private static final String SYSTEMD_UNIT = "pieria-daemon";

  private static String launchdTarget() {
    return "gui/" + uid() + "/" + LAUNCHD_LABEL;
  }

  private static Path launchdPlist() {
    return Path.of(System.getProperty("user.home", "."), "Library", "LaunchAgents", LAUNCHD_LABEL + ".plist");
  }

  /**
   * True when the job is bootstrapped into the gui domain (regardless of whether it is running).
   */
  private static boolean launchdLoaded() {
    return run(List.of("launchctl", "print", launchdTarget())).exitCode() == 0;
  }

  private static List<String> buildSpawnCommand(Path daemon, StartOptions opts) {
    List<String> command = new ArrayList<>();
    if (daemon.getFileName().toString().endsWith(".jar")) {
      command.add(javaExecutable());
      command.add("-jar");
      command.add(daemon.toString());
    } else {
      command.add(daemon.toString());
    }
    command.add("--pieria.daemon.host=" + opts.host());
    command.add("--pieria.daemon.port=" + opts.port());
    if (opts.runtimeDir() != null && !opts.runtimeDir().isBlank()) {
      command.add("--pieria.app-data.runtime-dir=" + opts.runtimeDir());
    }
    return command;
  }

  /**
   * Resolve the daemon executable: explicit → env → PATH → ~/.local/bin → nearby jar.
   */
  private static Optional<Path> locateDaemon(String explicit) {
    List<String> candidates = new ArrayList<>();
    if (explicit != null && !explicit.isBlank()) {
      candidates.add(explicit);
    }
    String env = System.getenv("PIERIA_DAEMON_BIN");
    if (env != null && !env.isBlank()) {
      candidates.add(env);
    }
    for (String c : candidates) {
      Path p = Path.of(c);
      if (Files.isRegularFile(p)) {
        return Optional.of(p.toAbsolutePath());
      }
    }

    String exeName = os().contains("win") ? "pieria-daemon.exe" : "pieria-daemon";
    Optional<Path> onPath = findOnPath(exeName);
    if (onPath.isPresent()) {
      return onPath;
    }

    String home = System.getProperty("user.home", ".");
    List<Path> wellKnown = List.of(
      Path.of(home, ".local", "bin", exeName),
      Path.of(home, ".local", "bin", "pieria.jar"));
    for (Path p : wellKnown) {
      if (Files.isRegularFile(p)) {
        return Optional.of(p.toAbsolutePath());
      }
    }
    return Optional.empty();
  }

  private static Optional<Path> findOnPath(String exeName) {
    String pathEnv = System.getenv("PATH");
    if (pathEnv == null || pathEnv.isBlank()) {
      return Optional.empty();
    }
    for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
      if (dir.isBlank()) {
        continue;
      }
      Path candidate = Path.of(dir, exeName);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
        return Optional.of(candidate.toAbsolutePath());
      }
    }
    return Optional.empty();
  }

  private static boolean waitForExit(ProcessHandle process) {
    try {
      process.onExit().get(10, TimeUnit.SECONDS);
      return true;
    } catch (Exception e) {
      return !process.isAlive();
    }
  }

  private static Result run(List<String> command) {
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes());
      boolean finished = process.waitFor(15, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        return new Result(-1, "timed out");
      }
      return new Result(process.exitValue(), output);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return new Result(-1, e.getMessage());
    }
  }

  /**
   * Numeric uid for launchd's {@code gui/<uid>} domain target.
   */
  private static String uid() {
    Result r = run(List.of("id", "-u"));
    String value = r.output() == null ? "" : r.output().strip();
    return value.isEmpty() ? "0" : value;
  }

  private static String javaExecutable() {
    String javaHome = System.getProperty("java.home");
    if (javaHome != null && !javaHome.isBlank()) {
      Path candidate = Path.of(javaHome, "bin", os().contains("win") ? "java.exe" : "java");
      if (Files.isRegularFile(candidate)) {
        return candidate.toString();
      }
    }
    return "java";
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // best-effort cleanup
    }
  }

  private static boolean isLinux() {
    String os = os();
    return !os.contains("mac") && !os.contains("win");
  }

  private static String os() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
  }

  public StartOutcome start(StartOptions opts) {
    Service service = detectService();
    if (service != null) {
      return startViaService(service, opts.dryRun());
    }
    return spawn(opts);
  }

  // --- OS service path -------------------------------------------------------------------------

  public StopOutcome stop(StopOptions opts) {
    Service service = detectService();
    if (service != null) {
      return stopViaService(service, opts.dryRun());
    }
    return stopSpawned(opts);
  }

  private Service detectService() {
    String home = System.getProperty("user.home", ".");
    if (os().contains("mac")
      && Files.exists(Path.of(home, "Library", "LaunchAgents", LAUNCHD_LABEL + ".plist"))) {
      return Service.LAUNCHD;
    }
    if (isLinux()
      && Files.exists(Path.of(home, ".config", "systemd", "user", SYSTEMD_UNIT + ".service"))) {
      return Service.SYSTEMD;
    }
    // Windows service management is not auto-detected here; users control it via the PowerShell
    // service script. Spawn fallback still works for ad-hoc runs.
    return null;
  }

  private StartOutcome startViaService(Service service, boolean dryRun) {
    return switch (service) {
      case LAUNCHD -> startLaunchd(dryRun);
      case SYSTEMD -> runServiceCommands(
        "systemd --user", dryRun,
        List.of(List.of("systemctl", "--user", "start", SYSTEMD_UNIT)),
        StartedViaService::new, Failed::new);
    };
  }

  private StopOutcome stopViaService(Service service, boolean dryRun) {
    return switch (service) {
      case LAUNCHD -> stopLaunchd(dryRun);
      case SYSTEMD -> runServiceCommands(
        "systemd --user", dryRun,
        List.of(List.of("systemctl", "--user", "stop", SYSTEMD_UNIT)),
        StoppedViaService::new, Failed::new);
    };
  }

  /**
   * launchd start: bootstrap the job into the gui domain if it isn't loaded yet (e.g. after a
   * {@code stop} that booted it out), then enable + kickstart. {@code bootstrap-if-missing} keeps
   * this symmetric with {@link #stopLaunchd(boolean)}, which boots the job out entirely.
   */
  private StartOutcome startLaunchd(boolean dryRun) {
    String target = launchdTarget();
    List<String> bootstrap = List.of("launchctl", "bootstrap", "gui/" + uid(), launchdPlist().toString());
    List<String> enable = List.of("launchctl", "enable", target);
    List<String> kickstart = List.of("launchctl", "kickstart", "-k", target);

    if (dryRun) {
      Stream.of(bootstrap, enable, kickstart).forEach(c -> System.out.println(String.join(" ", c)));
      return new StartedViaService("launchd (dry-run)");
    }
    if (!launchdLoaded()) {
      Result r = run(bootstrap);
      if (r.exitCode() != 0) {
        return new Failed("launchd start failed: " + r.errorSummary(bootstrap));
      }
    }
    for (List<String> command : List.of(enable, kickstart)) {
      Result r = run(command);
      if (r.exitCode() != 0) {
        return new Failed("launchd start failed: " + r.errorSummary(command));
      }
    }
    return new StartedViaService("launchd");
  }

  /**
   * launchd stop: the plist sets {@code KeepAlive{SuccessfulExit=false}}, so a bare {@code kill TERM}
   * is seen as an unsuccessful (signal) exit and launchd respawns the job immediately. {@code disable}
   * does not help — the override is only honored at bootstrap time, not when KeepAlive relaunches a
   * loaded job. {@code bootout} removes the job from the domain so it stays down; {@code start}
   * re-bootstraps it.
   */
  private StopOutcome stopLaunchd(boolean dryRun) {
    String target = launchdTarget();
    List<String> bootout = List.of("launchctl", "bootout", target);

    if (dryRun) {
      System.out.println(String.join(" ", bootout));
      return new StoppedViaService("launchd (dry-run)");
    }
    if (!launchdLoaded()) {
      return new NotRunning();
    }
    Result r = run(bootout);
    if (r.exitCode() != 0) {
      return new Failed("launchd stop failed: " + r.errorSummary(bootout));
    }
    return new StoppedViaService("launchd");
  }

  private <T> T runServiceCommands(
    String name, boolean dryRun, List<List<String>> commands,
    java.util.function.Function<String, T> onSuccess, java.util.function.Function<String, T> onFailure) {
    if (dryRun) {
      commands.forEach(c -> System.out.println(String.join(" ", c)));
      return onSuccess.apply(name + " (dry-run)");
    }
    for (List<String> command : commands) {
      Result r = run(command);
      if (r.exitCode() != 0) {
        return onFailure.apply(name + " action failed: " + r.errorSummary(command));
      }
    }
    return onSuccess.apply(name);
  }

  private StartOutcome spawn(StartOptions opts) {
    Optional<Path> binary = locateDaemon(opts.daemonBinary());
    if (binary.isEmpty()) {
      return new NoMechanism(
        "No Pieria service is installed and no daemon executable was found.\n"
          + "Install the daemon (it lands at ~/.local/bin/pieria-daemon), set $PIERIA_DAEMON_BIN, "
          + "or pass --daemon <path>.");
    }

    RuntimePaths paths = RuntimePaths.resolve(opts.runtimeDir());
    Path daemon = binary.get();
    List<String> command = buildSpawnCommand(daemon, opts);

    if (opts.dryRun()) {
      System.out.println(String.join(" ", command));
      System.out.println("# logs → " + paths.logsDir().resolve("pieria-daemon.out.log"));
      System.out.println("# pid  → " + paths.pidFile());
      return new Spawned(-1);
    }

    try {
      Files.createDirectories(paths.runtimeDir());
      Files.createDirectories(paths.logsDir());
      ProcessBuilder pb = new ProcessBuilder(command)
        .redirectOutput(paths.logsDir().resolve("pieria-daemon.out.log").toFile())
        .redirectError(paths.logsDir().resolve("pieria-daemon.err.log").toFile());
      Process process = pb.start();
      Files.writeString(paths.pidFile(), Long.toString(process.pid()));
      return new Spawned(process.pid());
    } catch (IOException e) {
      return new Failed("could not spawn daemon: " + e.getMessage());
    }
  }

  private StopOutcome stopSpawned(StopOptions opts) {
    RuntimePaths paths = RuntimePaths.resolve(opts.runtimeDir());
    Path pidFile = paths.pidFile();
    if (!Files.isRegularFile(pidFile)) {
      return new NotRunning();
    }

    long pid;
    try {
      pid = Long.parseLong(Files.readString(pidFile).trim());
    } catch (IOException | NumberFormatException e) {
      return new Failed("could not read PID file " + pidFile + ": " + e.getMessage());
    }

    Optional<ProcessHandle> handle = ProcessHandle.of(pid);
    if (handle.isEmpty() || !handle.get().isAlive()) {
      deleteQuietly(pidFile);
      return new NotRunning();
    }

    if (opts.dryRun()) {
      System.out.println("kill -TERM " + pid);
      return new StoppedPid(pid);
    }

    ProcessHandle process = handle.get();
    process.destroy();
    if (!waitForExit(process)) {
      process.destroyForcibly();
    }
    deleteQuietly(pidFile);
    return new StoppedPid(pid);
  }

  private enum Service {LAUNCHD, SYSTEMD}

  // --- Spawn fallback --------------------------------------------------------------------------

  /**
   * Discriminated start outcome.
   */
  public sealed interface StartOutcome
    permits AlreadyRunning, StartedViaService, Spawned, NoMechanism, Failed {
  }

  /**
   * Discriminated stop outcome. ({@link Failed} is shared with {@link StartOutcome}.)
   */
  public sealed interface StopOutcome permits NotRunning, StoppedViaService, StoppedPid, Failed {
  }

  /**
   * Inputs for {@link #start(StartOptions)}.
   *
   * @param daemonBinary explicit daemon executable/jar path ({@code --daemon}); {@code null} ⇒ auto-locate
   * @param runtimeDir   explicit runtime dir ({@code --runtime-dir}); {@code null} ⇒ OS default
   * @param host         daemon bind host, forwarded to a spawned process
   * @param port         daemon bind port, forwarded to a spawned process
   * @param dryRun       print the service/spawn command instead of executing it
   */
  public record StartOptions(String daemonBinary, String runtimeDir, String host, int port, boolean dryRun) {
  }

  /**
   * Inputs for {@link #stop(StopOptions)}.
   *
   * @param runtimeDir explicit runtime dir ({@code --runtime-dir}); {@code null} ⇒ OS default
   * @param dryRun     print the stop command instead of executing it
   */
  public record StopOptions(String runtimeDir, boolean dryRun) {
  }

  /**
   * The daemon was already reachable; nothing was started.
   */
  public record AlreadyRunning() implements StartOutcome {
  }

  /**
   * Start was delegated to the installed OS service manager.
   */
  public record StartedViaService(String detail) implements StartOutcome {
  }

  // --- small process helpers -------------------------------------------------------------------

  /**
   * A new detached daemon process was spawned with the given OS pid.
   */
  public record Spawned(long pid) implements StartOutcome {
  }

  /**
   * No service is installed and no daemon executable could be located; {@code guidance} explains
   * how to fix it.
   */
  public record NoMechanism(String guidance) implements StartOutcome {
  }

  /**
   * The start/stop attempt ran but failed; {@code detail} is the cause.
   */
  public record Failed(String detail) implements StartOutcome, StopOutcome {
  }

  /**
   * Nothing to stop: no service is managing the daemon and no live PID file was found.
   */
  public record NotRunning() implements StopOutcome {
  }

  /**
   * Stop was delegated to the installed OS service manager.
   */
  public record StoppedViaService(String detail) implements StopOutcome {
  }

  /**
   * A spawned process (tracked by PID file) was signalled to stop.
   */
  public record StoppedPid(long pid) implements StopOutcome {
  }

  private record Result(int exitCode, String output) {
    String errorSummary(List<String> command) {
      String trimmed = output == null ? "" : output.strip();
      return trimmed.isEmpty() ? ("exit " + exitCode + " from `" + String.join(" ", command) + "`") : trimmed;
    }
  }
}
