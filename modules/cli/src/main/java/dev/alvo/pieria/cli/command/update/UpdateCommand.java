package dev.alvo.pieria.cli.command.update;

import dev.alvo.pieria.cli.modules.daemon.DaemonClient;
import dev.alvo.pieria.cli.modules.daemon.DaemonProcess;
import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import dev.alvo.pieria.cli.modules.harness.HarnessRegistry;
import dev.alvo.pieria.cli.modules.harness.HookAssetWriter;
import dev.alvo.pieria.cli.modules.update.BinarySource;
import dev.alvo.pieria.cli.modules.update.BinarySwapper;
import dev.alvo.pieria.cli.modules.update.BuildInfo;
import dev.alvo.pieria.cli.modules.update.InstallLayout;
import dev.alvo.pieria.cli.modules.update.LocalDistSource;
import dev.alvo.pieria.cli.modules.update.Platform;
import dev.alvo.pieria.cli.modules.update.PlatformSupport;
import dev.alvo.pieria.cli.modules.update.ReleaseSource;
import dev.alvo.pieria.cli.modules.update.StagedDist;
import dev.alvo.pieria.cli.modules.update.UpdateException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code pieria update} — replace the installed binaries and restart the daemon in one step.
 *
 * <p>Acquires a new distribution (a published release by default, or a locally-built one via
 * {@code --from-build}/{@code --from}), stops the daemon, atomically swaps the binaries, refreshes
 * the embedded hook scripts where a harness was wired, restarts the daemon, and waits for it to come
 * healthy. A daemon restart is transparent to a live Claude Code session; only a changed gateway or
 * hook script requires relaunching the harness.
 *
 * <p>Acquisition happens before the daemon is stopped, so a failed download never leaves the system
 * serviceless. A failed swap rolls back and the daemon is restarted regardless.
 */
@Command(
  name = "update",
  description = "Update Pieria's binaries and restart the daemon.",
  mixinStandardHelpOptions = true
)
public final class UpdateCommand implements Callable<Integer> {

  @Option(names = "--version", description = "Release tag to install (default: latest). Ignored with --from/--from-build.")
  String version;

  @Option(names = "--from-build", description = "Install from the repo's local dist (modules/daemon/build/distributions/pieria-native).")
  boolean fromBuild;

  @Option(names = "--from", description = "Install from an explicit distribution directory (containing bin/...).")
  String from;

  @Option(names = "--jar", description = "Use the JVM distribution (lib/*.jar) instead of native binaries. Local sources only.")
  boolean jar;

  @Option(names = "--force", description = "Swap even if the installed version already matches the release.")
  boolean force;

  @Option(names = "--no-restart", description = "Swap binaries only; do not stop/start the daemon.")
  boolean noRestart;

  @Option(names = "--no-harness-refresh", description = "Do not re-extract the lifecycle hook scripts.")
  boolean noHarnessRefresh;

  @Option(names = "--daemon-url", description = "Daemon base URL (default: $PIERIA_DAEMON_URL or http://127.0.0.1:8077).")
  String daemonUrl;

  @Option(names = "--install-dir", description = "Install root to update (default: auto-detected from the installed `pieria`).")
  String installDir;

  @Option(names = "--timeout", description = "Seconds to wait for the daemon to become healthy after restart (default: 20).")
  int timeoutSeconds = 20;

  @Option(names = "--dry-run", description = "Print the planned actions without changing anything.")
  boolean dryRun;

  private static String host(String url) {
    String host = URI.create(url).getHost();
    return host == null ? "127.0.0.1" : host;
  }

  private static int port(String url) {
    int port = URI.create(url).getPort();
    return port == -1 ? 8077 : port;
  }

  @Override
  public Integer call() {
    Platform platform = PlatformSupport.detect();
    if (!platform.supported()) {
      System.err.printf("`pieria update` does not support %s yet. Re-run the installer to update:%n", platform.slug());
      System.err.println("  curl -fsSL https://raw.githubusercontent.com/VAlux/pieria/main/packaging/install.sh | bash");
      return 2;
    }

    BinarySource source;
    try {
      source = chooseSource(platform);
    } catch (UpdateException e) {
      System.err.println("error: " + e.getMessage());
      return 64;
    }

    InstallLayout install = InstallLayout.resolve(
      System::getenv,
      Path.of(System.getProperty("user.home", ".")),
      platform,
      installDir == null ? null : Path.of(installDir));

    boolean releaseSource = source instanceof ReleaseSource;

    if (dryRun) {
      return printPlan(source, install, releaseSource);
    }

    // 1. Acquire first — before touching the running daemon.
    StagedDist dist;
    try {
      dist = source.resolve();
    } catch (UpdateException e) {
      System.err.println("error: " + e.getMessage());
      return 1;
    }

    String oldVersion = BuildInfo.readFrom(install.binDir());
    String newVersion = BuildInfo.readFrom(dist.binDir());

    // 2. Skip a redundant release update (local sources always swap — the user explicitly built them).
    if (releaseSource && !force && BuildInfo.isKnown(oldVersion) && oldVersion.equals(newVersion)) {
      System.out.printf("Already up to date (%s). Use --force to reinstall.%n", oldVersion);
      return 0;
    }

    String url = DaemonUrls.resolve(daemonUrl);
    DaemonClient client = new DaemonClient(url);
    DaemonProcess daemon = new DaemonProcess();
    boolean restart = !noRestart;

    // 3. Stop the daemon (transparent to live MCP sessions, which reconnect over HTTP).
    if (restart) {
      stopDaemon(daemon);
    }

    // 4. Swap binaries (atomic; rolls back internally on failure).
    try {
      new BinarySwapper(platform).swap(dist, install);
      System.out.printf("Swapped binaries in %s.%n", install.binDir());
    } catch (UpdateException e) {
      System.err.println("error: " + e.getMessage());
      if (restart) {
        startDaemon(daemon, client, url); // bring the previous version back up
      }
      return 1;
    }

    // 5. Refresh hook scripts where a harness was wired.
    if (!noHarnessRefresh) {
      refreshHooks(install, false);
    }

    // 6. Restart and wait for health.
    if (restart) {
      startDaemon(daemon, client, url);
    }

    report(oldVersion, newVersion, releaseSource);
    return 0;
  }

  private BinarySource chooseSource(Platform platform) {
    boolean local = fromBuild || from != null;
    if (fromBuild && from != null) {
      throw new UpdateException("--from-build and --from are mutually exclusive.");
    }
    if (version != null && local) {
      throw new UpdateException("--version applies to release downloads, not --from/--from-build.");
    }
    if (jar && !local) {
      throw new UpdateException("--jar applies to local sources only; releases ship native binaries.");
    }
    if (from != null) {
      return new LocalDistSource(Path.of(from), jar, platform);
    }
    if (fromBuild) {
      return new LocalDistSource(defaultBuildDir(jar), jar, platform);
    }
    return new ReleaseSource(platform, version);
  }

  private Path defaultBuildDir(boolean jar) {
    return Path.of("modules", "daemon", "build", "distributions", jar ? "pieria-jvm" : "pieria-native");
  }

  private int printPlan(BinarySource source, InstallLayout install, boolean releaseSource) {
    System.out.println("Plan (dry-run, nothing changed):");
    System.out.println("  source:   " + source.describe());
    System.out.println("  install:  " + install.binDir());
    if (!noRestart) {
      System.out.println("  daemon:   stop, swap, start, wait for /pieria-health");
    } else {
      System.out.println("  daemon:   left running (--no-restart)");
    }
    System.out.println("  binaries: " + BinarySource.BINARIES);
    if (!noHarnessRefresh) {
      List<Path> harness = install.existingHarnessDirs();
      System.out.println("  hooks:    " + (harness.isEmpty() ? "none wired; skip" : "refresh " + harness));
    }
    if (releaseSource) {
      System.out.println("  note:     would skip if installed version already matches (unless --force)");
    }
    return 0;
  }

  private void stopDaemon(DaemonProcess daemon) {
    switch (daemon.stop(new DaemonProcess.StopOptions(null, false))) {
      case DaemonProcess.StoppedViaService s -> System.out.printf("Stopped daemon via %s.%n", s.detail());
      case DaemonProcess.StoppedPid s -> System.out.printf("Stopped daemon (pid %d).%n", s.pid());
      case DaemonProcess.NotRunning ignored -> System.out.println("Daemon was not running.");
      case DaemonProcess.Failed f -> System.err.printf("warning: could not stop daemon (%s); continuing.%n", f.detail());
    }
  }

  private void startDaemon(DaemonProcess daemon, DaemonClient client, String url) {
    DaemonProcess.StartOptions opts = new DaemonProcess.StartOptions(null, null, host(url), port(url), false);
    switch (daemon.start(opts)) {
      case DaemonProcess.StartedViaService s -> System.out.printf("Started daemon via %s.%n", s.detail());
      case DaemonProcess.Spawned s -> System.out.printf("Spawned daemon (pid %d).%n", s.pid());
      case DaemonProcess.AlreadyRunning ignored -> System.out.println("Daemon already running.");
      case DaemonProcess.NoMechanism n -> System.err.println("warning: " + n.guidance());
      case DaemonProcess.Failed f -> System.err.printf("warning: could not start daemon: %s%n", f.detail());
    }
    if (client.awaitHealthy(timeoutSeconds)) {
      System.out.printf("Daemon healthy at %s.%n", url);
    } else {
      System.err.printf("warning: daemon did not become healthy within %ds; check its logs.%n", timeoutSeconds);
    }
  }

  private void refreshHooks(InstallLayout install, boolean dryRun) {
    List<Path> dirs = install.existingHarnessDirs();
    if (dirs.isEmpty()) {
      return; // no harness wired; nothing references the scripts
    }
    HarnessRegistry registry = new HarnessRegistry();
    List<String> resources = registry.all().stream()
      .flatMap(installer -> installer.requiredScriptResources().stream())
      .distinct()
      .toList();
    HookAssetWriter writer = new HookAssetWriter();
    for (Path dir : dirs) {
      try {
        writer.extract(dir, resources, dryRun, System.out);
      } catch (IOException e) {
        System.err.printf("warning: could not refresh hook scripts in %s: %s%n", dir, e.getMessage());
      }
    }
  }

  private void report(String oldVersion, String newVersion, boolean releaseSource) {
    if (releaseSource && BuildInfo.isKnown(oldVersion) && BuildInfo.isKnown(newVersion)) {
      System.out.printf("Updated %s → %s.%n", oldVersion, newVersion);
    } else if (BuildInfo.isKnown(newVersion)) {
      System.out.printf("Deployed %s.%n", newVersion);
    } else {
      System.out.println("Update complete.");
    }
    System.out.println("Restart Claude Code only if the gateway binary or hook scripts changed.");
  }
}
