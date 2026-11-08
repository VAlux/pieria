package dev.alvo.pieria.harvester.conformance;

import dev.alvo.pieria.harvester.protocol.BoundedLineReader;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;
import dev.alvo.pieria.harvester.protocol.ProtocolViolation;
import dev.alvo.pieria.harvester.protocol.RecordCodec;
import dev.alvo.pieria.harvester.protocol.RequestCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/** Launches the harvester the way the daemon will: scrubbed environment, envelope on stdin. */
final class ProcessRun {

  private static final int MAX_STDERR_BYTES = 64 * 1024;
  private static final long REAP_MILLIS = 2_000;
  private static final List<String> KEPT_ENVIRONMENT = List.of("PATH", "HOME", "SystemRoot");

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

  private static Process start(ConformanceTarget target, String command, HarvestRequest request) throws IOException {
    List<String> commandLine = new ArrayList<>(target.command());
    commandLine.add(command);
    ProcessBuilder builder = new ProcessBuilder(commandLine).directory(target.workingDir().toFile());
    Map<String, String> environment = builder.environment();
    environment.clear();
    KEPT_ENVIRONMENT.forEach(name -> {
      String value = System.getenv(name);
      if (value != null) {
        environment.put(name, value);
      }
    });
    Process process = builder.start();
    // On its own thread: a harvester that never reads stdin must not stall the kit on a full pipe.
    Thread.startVirtualThread(() -> {
      try (OutputStream stdin = process.getOutputStream()) {
        stdin.write((RequestCodec.encode(request) + "\n").getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        // The harvester exited or closed stdin; its exit status tells the story.
      }
    });
    return process;
  }

  private static void destroyTree(Process process) {
    try (Stream<ProcessHandle> descendants = process.descendants()) {
      descendants.forEach(ProcessHandle::destroyForcibly);
    }
    process.destroyForcibly();
  }

  private static void drain(InputStream stderr, ByteArrayOutputStream sink) {
    Thread.startVirtualThread(() -> {
      byte[] buffer = new byte[4096];
      try {
        for (int read = stderr.read(buffer); read != -1; read = stderr.read(buffer)) {
          synchronized (sink) {
            sink.write(buffer, 0, Math.max(0, Math.min(MAX_STDERR_BYTES - sink.size(), read)));
          }
        }
      } catch (IOException e) {
        // The process is gone; keep what was captured.
      }
    });
  }

  /**
   * Reads on a separate thread, because a descendant that inherited stdout can hold the pipe open
   * after the harvester exits. At the deadline the kit abandons that thread and moves on.
   */
  private static String readRecords(Process process, List<HarvestRecord> records, long timeoutMillis)
    throws TimeoutException, InterruptedException {
    CompletableFuture<String> reading = new CompletableFuture<>();
    Thread.startVirtualThread(() -> {
      try {
        BoundedLineReader reader = new BoundedLineReader(process.getInputStream(), ProtocolLimits.MAX_LINE_BYTES);
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          HarvestRecord record = RecordCodec.decode(line);
          synchronized (records) {
            records.add(record);
          }
        }
        reading.complete(null);
      } catch (ProtocolViolation e) {
        reading.complete(e.getMessage());
      }
    });
    try {
      return reading.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      return String.valueOf(e.getCause());
    }
  }

  static Outcome execute(ConformanceTarget target, String command, HarvestRequest request, Duration timeout) {
    Process process;
    try {
      process = start(target, command, request);
    } catch (IOException e) {
      return new Outcome(false, -1, List.of(), "could not start: " + e.getMessage(), "");
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    drain(process.getErrorStream(), stderr);

    List<HarvestRecord> records = new ArrayList<>();
    String violation = null;
    boolean timedOut = false;
    try {
      violation = readRecords(process, records, timeout.toMillis());
      if (violation != null) {
        destroyTree(process);
      } else {
        timedOut = !process.waitFor(Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())), TimeUnit.MILLISECONDS);
      }
    } catch (TimeoutException e) {
      timedOut = true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      violation = "interrupted";
    }
    if (timedOut) {
      destroyTree(process);
    }

    int exitCode = awaitExit(process);
    String captured;
    synchronized (stderr) {
      captured = stderr.toString(StandardCharsets.UTF_8);
    }
    List<HarvestRecord> snapshot;
    synchronized (records) {
      snapshot = List.copyOf(records);
    }
    return new Outcome(timedOut, exitCode, snapshot, timedOut ? null : violation, captured);
  }

  private static int awaitExit(Process process) {
    try {
      return process.waitFor(REAP_MILLIS, TimeUnit.MILLISECONDS) ? process.exitValue() : -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  /** Reads one line, closes the harvester's stdout and reports whether it exits within {@code grace}. */
  static CloseResult exitsWhenStdoutCloses(ConformanceTarget target, HarvestRequest request, Duration timeout, Duration grace) {
    Process process;
    try {
      process = start(target, "harvest", request);
    } catch (IOException e) {
      return CloseResult.NO_OUTPUT;
    }
    drain(process.getErrorStream(), new ByteArrayOutputStream());
    try {
      CompletableFuture<Boolean> firstLine = CompletableFuture.supplyAsync(() -> {
        try {
          return new BoundedLineReader(process.getInputStream(), ProtocolLimits.MAX_LINE_BYTES).readLine() != null;
        } catch (ProtocolViolation e) {
          return false;
        }
      }, command -> Thread.startVirtualThread(command));
      if (!firstLine.get(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        return CloseResult.NO_OUTPUT;
      }
      if (!process.isAlive()) {
        return CloseResult.ALREADY_FINISHED;
      }
      process.getInputStream().close();
      return process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS) ? CloseResult.EXITED : CloseResult.STILL_RUNNING;
    } catch (TimeoutException | ExecutionException | IOException e) {
      return CloseResult.NO_OUTPUT;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return CloseResult.NO_OUTPUT;
    } finally {
      destroyTree(process);
    }
  }
}
