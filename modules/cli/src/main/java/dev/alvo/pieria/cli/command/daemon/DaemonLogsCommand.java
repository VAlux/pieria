package dev.alvo.pieria.cli.command.daemon;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.modules.daemon.LogTailer;
import dev.alvo.pieria.cli.modules.daemon.RuntimePaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code pieria daemon logs} — tail the current daemon log to the terminal.
 *
 * <p>Reads the on-disk log files directly rather than streaming over the daemon API, so it keeps
 * working when the daemon is down or has crashed. Tailing is pure Java (no {@code tail -f}), so it
 * behaves the same on every supported platform.
 *
 * <p>The "current" log file depends on how the daemon was started: a CLI-spawned daemon logs to
 * {@code pieria-daemon.out.log} (stdout redirect), while a service-managed one (launchd/systemd)
 * logs to {@code pieria-daemon.log} (Spring {@code logging.file.name}). By default the
 * most-recently-modified of those is chosen; {@code --err} and {@code --file} override.
 *
 * <p>Exit codes: {@code 0} ok, {@code 1} no log file found (non-follow).
 */
@Command(
  name = "logs",
  description = "Tail the current daemon log (works even when the daemon is down).",
  mixinStandardHelpOptions = true
)
public final class DaemonLogsCommand implements Callable<Integer> {

  private static final List<String> CANDIDATES = List.of("pieria-daemon.log", "pieria-daemon.out.log");
  private static final long POLL_MILLIS = 300;

  @Option(names = {"-f", "--follow"}, description = "Keep streaming new lines until interrupted (Ctrl-C).")
  boolean follow;

  @Option(names = {"-n", "--lines"}, description = "Number of trailing lines to print initially (default: 200).")
  int lines = 200;

  @Option(names = "--logs-dir", description = "Logs directory to read from (default: OS app-data logs dir).")
  String logsDir;

  @Option(names = "--err", description = "Tail the daemon's stderr log (pieria-daemon.err.log) instead.")
  boolean err;

  @Option(names = "--file", description = "Tail an explicit log file (overrides --err and auto-selection).")
  String file;

  private final Logger log = new Logger();

  @Override
  public Integer call() {
    Path dir = (logsDir != null && !logsDir.isBlank())
      ? Path.of(logsDir).toAbsolutePath()
      : RuntimePaths.resolve(null).logsDir();

    Path target = resolveTarget(dir);

    if (target == null || !Files.isRegularFile(target)) {
      if (!follow) {
        log.error("No daemon log file found in {}.", dir);
        log.error("The daemon may not have started yet — try 'pieria daemon start'.");
        return 1;
      }
      target = (target != null) ? target : dir.resolve(CANDIDATES.get(0));
      log.info("Waiting for {} to appear… (Ctrl-C to stop)", target);
      if (!awaitFile(target)) {
        return 0; // interrupted while waiting.
      }
    }

    for (String line : LogTailer.lastLines(target, lines)) {
      log.info("{}", line);
    }

    return follow ? followLoop(target) : 0;
  }

  private Path resolveTarget(Path dir) {
    if (file != null && !file.isBlank()) {
      return Path.of(file).toAbsolutePath();
    }
    if (err) {
      return dir.resolve("pieria-daemon.err.log");
    }
    return mostRecent(dir);
  }

  /**
   * The most-recently-modified existing candidate log file, or {@code null} if none exist.
   */
  private Path mostRecent(Path dir) {
    Path best = null;
    long bestModified = Long.MIN_VALUE;
    for (String name : CANDIDATES) {
      Path candidate = dir.resolve(name);
      if (!Files.isRegularFile(candidate)) {
        continue;
      }
      try {
        long modified = Files.getLastModifiedTime(candidate).toMillis();
        if (modified >= bestModified) {
          bestModified = modified;
          best = candidate;
        }
      } catch (IOException ignored) {
        // Skip files we cannot stat.
      }
    }
    return best;
  }

  private boolean awaitFile(Path target) {
    while (!Files.isRegularFile(target)) {
      if (!sleep()) {
        return false;
      }
    }
    return true;
  }

  private int followLoop(Path target) {
    long position = LogTailer.size(target);
    while (true) {
      if (!sleep()) {
        return 0; // interrupted — clean exit.
      }
      LogTailer.Chunk chunk = LogTailer.readFrom(target, position);
      position = chunk.newPosition();
      if (!chunk.text().isEmpty()) {
        log.print("{}", chunk.text());
      }
    }
  }

  /**
   * Sleep one poll interval. Returns {@code false} if interrupted (restoring the interrupt flag).
   */
  private boolean sleep() {
    try {
      Thread.sleep(POLL_MILLIS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
