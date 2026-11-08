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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/** Launches the harvester the way the daemon will: scrubbed environment, envelope on stdin. */
final class ProcessRun {

  private static final int MAX_STDERR_BYTES = 64 * 1024;
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

  /** Destroys the process at the deadline, which unblocks a reader waiting on its output. */
  private static Thread watchdog(Process process, Duration timeout, AtomicBoolean timedOut) {
    return Thread.startVirtualThread(() -> {
      try {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
          timedOut.set(true);
          destroyTree(process);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
  }

  private static Thread drain(InputStream stderr, ByteArrayOutputStream sink) {
    return Thread.startVirtualThread(() -> {
      byte[] buffer = new byte[4096];
      try {
        for (int read = stderr.read(buffer); read != -1; read = stderr.read(buffer)) {
          synchronized (sink) {
            int room = MAX_STDERR_BYTES - sink.size();
            sink.write(buffer, 0, Math.max(0, Math.min(room, read)));
          }
        }
      } catch (IOException e) {
        // The process is gone; keep what was captured.
      }
    });
  }

  static Outcome execute(ConformanceTarget target, String command, HarvestRequest request, Duration timeout) {
    Process process;
    try {
      process = start(target, command, request);
    } catch (IOException e) {
      return new Outcome(false, -1, List.of(), "could not start: " + e.getMessage(), "");
    }
    AtomicBoolean timedOut = new AtomicBoolean();
    Thread watchdog = watchdog(process, timeout, timedOut);
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    Thread stderrDrain = drain(process.getErrorStream(), stderr);

    List<HarvestRecord> records = new ArrayList<>();
    String violation = null;
    try (BoundedLineReader reader = new BoundedLineReader(process.getInputStream(), ProtocolLimits.MAX_LINE_BYTES)) {
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        records.add(RecordCodec.decode(line));
      }
    } catch (ProtocolViolation e) {
      violation = e.getMessage();
      destroyTree(process);
    } catch (IOException e) {
      violation = e.getMessage();
    }

    try {
      int exitCode = process.waitFor();
      watchdog.interrupt();
      stderrDrain.join(1_000);
      String captured;
      synchronized (stderr) {
        captured = stderr.toString(StandardCharsets.UTF_8);
      }
      return new Outcome(timedOut.get(), exitCode, List.copyOf(records), timedOut.get() ? null : violation, captured);
    } catch (InterruptedException e) {
      destroyTree(process);
      Thread.currentThread().interrupt();
      return new Outcome(false, -1, List.copyOf(records), "interrupted", "");
    }
  }

  /** Reads one record, closes the harvester's stdout and reports whether it exits within {@code grace}. */
  static boolean exitsWhenStdoutCloses(ConformanceTarget target, HarvestRequest request, Duration timeout, Duration grace) {
    Process process;
    try {
      process = start(target, "harvest", request);
    } catch (IOException e) {
      return false;
    }
    AtomicBoolean timedOut = new AtomicBoolean();
    Thread watchdog = watchdog(process, timeout, timedOut);
    drain(process.getErrorStream(), new ByteArrayOutputStream());
    try {
      new BoundedLineReader(process.getInputStream(), ProtocolLimits.MAX_LINE_BYTES).readLine();
    } catch (ProtocolViolation e) {
      destroyTree(process);
      return false;
    }
    try {
      process.getInputStream().close();
      return process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS) && !timedOut.get();
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      watchdog.interrupt();
      destroyTree(process);
    }
  }
}
