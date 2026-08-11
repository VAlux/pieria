package dev.alvo.pieria.cli.command.update;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.client.exception.DaemonInterruptedException;
import dev.alvo.pieria.client.HealthClient;
import dev.alvo.pieria.cli.modules.daemon.DaemonProcessController;
import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
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

import java.time.Duration;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code pieria update} — replace the installed binaries and restart the daemon in one step.
 *
 * <p>Acquires a new distribution (a published release by default, or a locally-built one via
 * {@code --from-build}/{@code --from}), stops the daemon, atomically swaps the binaries, restarts the
 * daemon, and waits for it to come healthy. A daemon restart is transparent to a live Claude Code
 * session; the lifecycle hooks live inside the {@code pieria} binary itself, so swapping it updates
 * them automatically — only a changed gateway binary requires relaunching the harness.
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

  private final Logger log = new Logger();

  @Option(names = "--version", description = "Release tag to install (default: latest). Ignored with --from/--from-build.")
  String version;

  @Option(names = "--from-build", description = "Install from the repo's local dist (modules/daemon/build/distributions/pieria-native).")
  boolean fromBuild;

  @Option(names = "--from", description = "Install from an explicit distribution directory (containing bin/...).")
  String from;

  @Option(names = "--force", description = "Swap even if the installed version already matches the release.")
  boolean force;

  @Option(names = "--no-restart", description = "Swap binaries only; do not stop/start the daemon.")
  boolean noRestart;

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

    BinarySource source;
    try {
      source = chooseSource(platform);
    } catch (UpdateException e) {
      log.error("error: {}", e.getMessage());
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
      log.error("error: {}", e.getMessage());
      return 1;
    }

    String oldVersion = BuildInfo.readFrom(install.binDir());
    String newVersion = BuildInfo.readFrom(dist.binDir());

    // 2. Skip a redundant release update (local sources always swap — the user explicitly built them).
    if (releaseSource && !force && BuildInfo.isKnown(oldVersion) && oldVersion.equals(newVersion)) {
      log.info("Already up to date ({}). Use --force to reinstall.", oldVersion);
      return 0;
    }

    String url = DaemonUrls.resolve(daemonUrl);
    HealthClient client = new HealthClient(url);
    DaemonProcessController daemon = new DaemonProcessController();
    boolean restart = !noRestart;

    // 3. Stop the daemon (transparent to live MCP sessions, which reconnect over HTTP).
    if (restart) {
      stopDaemon(daemon);
    }

    // 4. Swap binaries (atomic; rolls back internally on failure).
    try {
      new BinarySwapper(platform).swap(dist, install);
      log.info("Swapped binaries in {}.", install.binDir());
    } catch (UpdateException e) {
      log.error("error: {}", e.getMessage());
      if (restart) {
        startDaemon(daemon, client, url); // bring the previous version back up
      }
      return 1;
    }

    // 5. Restart and wait for health.
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
    if (from != null) {
      return new LocalDistSource(Path.of(from), platform);
    }
    if (fromBuild) {
      return new LocalDistSource(Path.of("modules", "daemon", "build", "distributions", "pieria-native"), platform);
    }
    return new ReleaseSource(platform, version);
  }

  private int printPlan(BinarySource source, InstallLayout install, boolean releaseSource) {
    log.info("Plan (dry-run, nothing changed):");
    log.info("  source:   {}", source.describe());
    log.info("  install:  {}", install.binDir());
    if (!noRestart) {
      log.info("  daemon:   stop, swap, start, wait for /pieria-health");
    } else {
      log.info("  daemon:   left running (--no-restart)");
    }
    log.info("  binaries: {}", BinarySource.BINARIES);
    if (releaseSource) {
      log.info("  note:     would skip if installed version already matches (unless --force)");
    }
    return 0;
  }

  private void stopDaemon(DaemonProcessController daemon) {
    switch (daemon.stop(new DaemonProcessController.StopOptions(null, false))) {
      case DaemonProcessController.StoppedViaService s -> log.info("Stopped daemon via {}.", s.detail());
      case DaemonProcessController.StoppedPid s -> log.info("Stopped daemon (pid {}).", s.pid());
      case DaemonProcessController.NotRunning ignored -> log.info("Daemon was not running.");
      case DaemonProcessController.Failed f -> log.error("warning: could not stop daemon ({}); continuing.", f.detail());
    }
  }

  private void startDaemon(DaemonProcessController daemon, HealthClient client, String url) {
    DaemonProcessController.StartOptions opts = new DaemonProcessController.StartOptions(null, null, host(url), port(url), false);
    switch (daemon.start(opts)) {
      case DaemonProcessController.StartedViaService s -> log.info("Started daemon via {}.", s.detail());
      case DaemonProcessController.Spawned s -> log.info("Spawned daemon (pid {}).", s.pid());
      case DaemonProcessController.AlreadyRunning ignored -> log.info("Daemon already running.");
      case DaemonProcessController.NoMechanism n -> log.error("warning: {}", n.guidance());
      case DaemonProcessController.Failed f -> log.error("warning: could not start daemon: {}", f.detail());
    }
    try {
      if (client.awaitReachable(Duration.ofSeconds(timeoutSeconds))) {
        log.info("Daemon healthy at {}.", url);
        return;
      }
    } catch (DaemonInterruptedException ignored) {
      // The shared client restored the interrupt flag; retain the command's warning-only behavior.
    }
    log.error("warning: daemon did not become healthy within {}s; check its logs.", timeoutSeconds);
  }

  private void report(String oldVersion, String newVersion, boolean releaseSource) {
    if (releaseSource && BuildInfo.isKnown(oldVersion) && BuildInfo.isKnown(newVersion)) {
      log.info("Updated {} → {}.", oldVersion, newVersion);
    } else if (BuildInfo.isKnown(newVersion)) {
      log.info("Deployed {}.", newVersion);
    } else {
      log.info("Update complete.");
    }
    log.info("Restart Claude Code only if the gateway binary changed.");
  }
}
