package dev.alvo.pieria.cli.command.daemon;

import dev.alvo.pieria.cli.modules.daemon.DaemonProcess;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code pieria daemon stop} — stop a running daemon.
 *
 * <p>Hybrid: if an OS service is installed the stop is delegated to it; otherwise the PID file
 * written by a CLI-spawned daemon is used to send SIGTERM (escalating to a forced kill if needed).
 */
@Command(
  name = "stop",
  description = "Stop the running Pieria daemon.",
  mixinStandardHelpOptions = true
)
public final class DaemonStopCommand implements Callable<Integer> {

  @Option(names = "--runtime-dir", description = "Runtime directory holding the PID file (default: OS app-data run dir).")
  String runtimeDir;

  @Option(names = "--dry-run", description = "Print the stop command without executing it.")
  boolean dryRun;

  @Override
  public Integer call() {
    DaemonProcess process = new DaemonProcess();

    return switch (process.stop(new DaemonProcess.StopOptions(runtimeDir, dryRun))) {
      case DaemonProcess.NotRunning ignored -> {
        System.out.println("Pieria daemon is not running.");
        yield 0;
      }
      case DaemonProcess.StoppedViaService s -> {
        System.out.printf("Stopped Pieria daemon via %s.%n", s.detail());
        yield 0;
      }
      case DaemonProcess.StoppedPid s -> {
        System.out.printf("Stopped Pieria daemon (pid %d).%n", s.pid());
        yield 0;
      }
      case DaemonProcess.Failed f -> {
        System.err.printf("Failed to stop daemon: %s%n", f.detail());
        yield 1;
      }
    };
  }
}
