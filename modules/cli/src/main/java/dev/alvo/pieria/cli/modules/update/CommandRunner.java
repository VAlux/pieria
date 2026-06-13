package dev.alvo.pieria.cli.modules.update;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external command and reports its exit status plus combined output. A functional seam so
 * the platform implementations ({@link MacOsPlatform}) can be unit-tested by injecting a fake that
 * records commands instead of spawning processes.
 */
@FunctionalInterface
public interface CommandRunner {

  Result run(List<String> command);

  /**
   * Real implementation: spawn the process, capture combined stdout/stderr, with a generous budget
   * (extraction of a release tarball can take a few seconds).
   */
  static CommandRunner real() {
    return command -> {
      try {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          return new Result(-1, "timed out: " + String.join(" ", command));
        }
        return new Result(process.exitValue(), output);
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        return new Result(-1, e.getMessage() == null ? e.toString() : e.getMessage());
      }
    };
  }

  /**
   * @param exitCode process exit code, or {@code -1} when the process could not be run
   * @param output   combined stdout/stderr (may be empty)
   */
  record Result(int exitCode, String output) {
    public boolean ok() {
      return exitCode == 0;
    }
  }
}
