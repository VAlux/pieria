package dev.alvo.pieria.harvester.conformance;

import dev.alvo.pieria.harvester.process.HarvesterProcess;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.ProtocolViolation;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/** Runs the harvester once through {@link HarvesterProcess} and gathers what it did. */
final class ProcessRun {

  private static final Duration REAP = Duration.ofSeconds(2);

  /** What one launch produced. {@code violation} is null when every line was valid. */
  record Outcome(boolean timedOut, int exitCode, List<HarvestRecord> records, String violation, String stderr) {

    HarvestRecord.Failure failure() {
      return records.stream().filter(HarvestRecord.Failure.class::isInstance)
        .map(HarvestRecord.Failure.class::cast).findFirst().orElse(null);
    }

    boolean completed() {
      return !timedOut && exitCode == 0 && violation == null && failure() == null;
    }

    String describe() {
      if (timedOut) {
        return "timed out";
      }
      if (violation != null) {
        return "protocol violation: " + violation;
      }
      HarvestRecord.Failure failure = failure();
      String reason = failure == null ? "" : ", error " + failure.errorClass().wire() + ": " + failure.message();
      return "exit " + exitCode + reason + (stderr.isBlank() ? "" : ", stderr: " + stderr.strip());
    }
  }

  /** What happened after the kit closed the harvester's stdout. */
  enum CloseResult { EXITED, STILL_RUNNING, ALREADY_FINISHED, NO_OUTPUT }

  private ProcessRun() {
  }

  static Outcome execute(ConformanceTarget target, String command, HarvestRequest request, Duration timeout) {
    HarvesterProcess process;
    try {
      process = HarvesterProcess.start(target.command(), command, target.workingDir(), request);
    } catch (IOException e) {
      return new Outcome(false, -1, List.of(), "could not start: " + e.getMessage(), "");
    }
    try (process) {
      Instant deadline = Instant.now().plus(timeout);
      List<HarvestRecord> records = new ArrayList<>();
      String violation = null;
      boolean timedOut = false;
      try {
        for (HarvestRecord record = process.next(deadline); record != null; record = process.next(deadline)) {
          records.add(record);
        }
        timedOut = !process.awaitExit(deadline);
      } catch (ProtocolViolation e) {
        violation = e.getMessage();
      } catch (TimeoutException e) {
        timedOut = true;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        violation = "interrupted";
      }
      if (timedOut || violation != null) {
        process.destroy();
      }
      return new Outcome(timedOut, reapedExitCode(process), List.copyOf(records), timedOut ? null : violation, process.stderr());
    }
  }

  private static int reapedExitCode(HarvesterProcess process) {
    try {
      process.awaitExit(Instant.now().plus(REAP));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return process.exitCode();
  }

  /** Reads one record, closes the harvester's stdout and reports whether it exits within {@code grace}. */
  static CloseResult exitsWhenStdoutCloses(ConformanceTarget target, HarvestRequest request, Duration timeout, Duration grace) {
    HarvesterProcess process;
    try {
      process = HarvesterProcess.start(target.command(), "harvest", target.workingDir(), request);
    } catch (IOException e) {
      return CloseResult.NO_OUTPUT;
    }
    try (process) {
      if (process.next(Instant.now().plus(timeout)) == null) {
        return CloseResult.NO_OUTPUT;
      }
      if (!process.isAlive()) {
        return CloseResult.ALREADY_FINISHED;
      }
      process.closeOutput();
      return process.awaitExit(Instant.now().plus(grace)) ? CloseResult.EXITED : CloseResult.STILL_RUNNING;
    } catch (ProtocolViolation | TimeoutException | IOException e) {
      return CloseResult.NO_OUTPUT;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return CloseResult.NO_OUTPUT;
    }
  }
}
