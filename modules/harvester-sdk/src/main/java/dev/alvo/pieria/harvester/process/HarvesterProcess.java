package dev.alvo.pieria.harvester.process;

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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * A running harvester: scrubbed environment, envelope on stdin, records from stdout.
 *
 * <p>A record is read only when {@link #next} asks for one, so a slow consumer leaves the harvester
 * blocked on a full pipe. Reading happens on a separate thread that {@link #next} can abandon at its
 * deadline, because a descendant that inherited stdout can hold the pipe open after the harvester
 * itself has exited.
 */
public final class HarvesterProcess implements AutoCloseable {

  private static final int MAX_STDERR_BYTES = 64 * 1024;
  private static final long STDERR_SETTLE_MILLIS = 500;
  private static final List<String> KEPT_ENVIRONMENT = List.of("PATH", "HOME", "SystemRoot", "USERPROFILE", "TEMP", "TMP",
    "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy");

  private sealed interface Read {
    record Line(HarvestRecord record) implements Read {
    }

    record End() implements Read {
    }

    record Violation(ProtocolViolation cause) implements Read {
    }
  }

  private final Process process;
  private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
  private final Thread stderrDrain;
  private final Semaphore demand = new Semaphore(0);
  private final LinkedBlockingQueue<Read> reads = new LinkedBlockingQueue<>(1);
  private final Thread reader;
  private boolean readPending;
  private Read terminal;

  private HarvesterProcess(Process process) {
    this.process = process;
    this.stderrDrain = Thread.startVirtualThread(this::drainStderr);
    this.reader = Thread.startVirtualThread(this::readOnDemand);
  }

  /**
   * Launches {@code baseCommand} followed by {@code subcommand}, and writes {@code request} to its stdin.
   */
  public static HarvesterProcess start(List<String> baseCommand, String subcommand, Path workingDir,
                                       HarvestRequest request) throws IOException {
    List<String> commandLine = new ArrayList<>(baseCommand);
    commandLine.add(subcommand);
    ProcessBuilder builder = new ProcessBuilder(commandLine).directory(workingDir.toFile());
    Map<String, String> environment = builder.environment();
    environment.clear();
    KEPT_ENVIRONMENT.forEach(name -> {
      String value = System.getenv(name);
      if (value != null) {
        environment.put(name, value);
      }
    });
    Process process = builder.start();
    // On its own thread: a harvester that never reads stdin must not stall the caller on a full pipe.
    Thread.startVirtualThread(() -> {
      try (OutputStream stdin = process.getOutputStream()) {
        stdin.write((RequestCodec.encode(request) + "\n").getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        // The harvester exited or closed stdin; its exit status tells the story.
      }
    });
    return new HarvesterProcess(process);
  }

  private void readOnDemand() {
    BoundedLineReader lines = new BoundedLineReader(process.getInputStream(), ProtocolLimits.MAX_LINE_BYTES);
    try {
      while (true) {
        demand.acquire();
        Read read;
        try {
          String line = lines.readLine();
          read = line == null ? new Read.End() : new Read.Line(RecordCodec.decode(line));
        } catch (ProtocolViolation e) {
          read = new Read.Violation(e);
        }
        reads.put(read);
        if (!(read instanceof Read.Line)) {
          return;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void drainStderr() {
    InputStream stream = process.getErrorStream();
    byte[] buffer = new byte[4096];
    try {
      for (int read = stream.read(buffer); read != -1; read = stream.read(buffer)) {
        synchronized (stderr) {
          stderr.write(buffer, 0, Math.max(0, Math.min(MAX_STDERR_BYTES - stderr.size(), read)));
        }
      }
    } catch (IOException e) {
      // The process is gone; keep what was captured.
    }
  }

  /**
   * The next record, or null at a clean end of output.
   *
   * @throws ProtocolViolation when the output breaks the protocol
   * @throws TimeoutException  when the deadline passes first; calling again keeps waiting for the same record
   */
  public HarvestRecord next(Instant deadline) throws TimeoutException, InterruptedException {
    if (terminal == null) {
      if (!readPending) {
        readPending = true;
        demand.release();
      }
      Read read = reads.poll(Math.max(0, Duration.between(Instant.now(), deadline).toMillis()), TimeUnit.MILLISECONDS);
      if (read == null) {
        throw new TimeoutException("no output before the deadline");
      }
      readPending = false;
      if (read instanceof Read.Line line) {
        return line.record();
      }
      terminal = read;
    }
    if (terminal instanceof Read.Violation violation) {
      throw violation.cause();
    }
    return null;
  }

  /** Whether the process exited before the deadline. */
  public boolean awaitExit(Instant deadline) throws InterruptedException {
    return process.waitFor(Math.max(0, Duration.between(Instant.now(), deadline).toMillis()), TimeUnit.MILLISECONDS);
  }

  /** The exit code, or -1 while the process is running. */
  public int exitCode() {
    return process.isAlive() ? -1 : process.exitValue();
  }

  public boolean isAlive() {
    return process.isAlive();
  }

  /** What the process wrote to stderr, up to a fixed cap. */
  public String stderr() {
    if (!process.isAlive()) {
      try {
        stderrDrain.join(STDERR_SETTLE_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    synchronized (stderr) {
      return stderr.toString(StandardCharsets.UTF_8);
    }
  }

  /** Closes the harvester's stdout, as if the reader had gone away. */
  public void closeOutput() throws IOException {
    process.getInputStream().close();
  }

  /** Kills the harvester and its descendants. */
  public void destroy() {
    try (Stream<ProcessHandle> descendants = process.descendants()) {
      descendants.forEach(ProcessHandle::destroyForcibly);
    }
    process.destroyForcibly();
    reader.interrupt();
  }

  @Override
  public void close() {
    destroy();
  }
}
