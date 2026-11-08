package dev.alvo.pieria.harvester;

import dev.alvo.pieria.harvester.protocol.BoundedLineReader;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;
import dev.alvo.pieria.harvester.protocol.ProtocolViolation;
import dev.alvo.pieria.harvester.protocol.RecordCodec;
import dev.alvo.pieria.harvester.protocol.RequestCodec;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Hosts a {@link Harvester} as a protocol-speaking process. */
public final class HarvesterRunner {

  public static final int EXIT_OK = 0;
  public static final int EXIT_FAILED = 1;
  public static final int EXIT_USAGE = 2;

  private HarvesterRunner() {
  }

  /**
   * The entry point for a harvester's {@code main}. Stdout is reserved for protocol records, so
   * anything else that prints to {@code System.out}, such as a library, is sent to stderr.
   */
  public static void main(Harvester harvester, String[] args) {
    OutputStream protocolOutput = new FileOutputStream(FileDescriptor.out);
    System.setOut(System.err);
    System.exit(run(harvester, args, System.in, protocolOutput, System.err));
  }

  /** Runs one command and returns the process exit code. */
  public static int run(Harvester harvester, String[] args, InputStream in, OutputStream out, PrintStream err) {
    if (args.length != 1 || !(args[0].equals("check") || args[0].equals("harvest"))) {
      err.println("usage: <harvester> check|harvest");
      return EXIT_USAGE;
    }
    StreamSink sink = new StreamSink(out);
    HarvestRequest request;
    try {
      request = readRequest(in);
    } catch (ProtocolViolation e) {
      sink.tryEmit(new HarvestRecord.Failure(ErrorClass.CONFIG, "bad envelope: " + e.getMessage(), null));
      return EXIT_USAGE;
    }
    try {
      if (args[0].equals("check")) {
        harvester.check(request);
      } else {
        harvester.harvest(request, sink);
      }
    } catch (HarvesterException e) {
      sink.tryEmit(new HarvestRecord.Failure(e.errorClass(), String.valueOf(e.getMessage()), e.retryAfterSeconds()));
      return EXIT_FAILED;
    } catch (SinkClosedException e) {
      return EXIT_FAILED;
    } catch (RuntimeException e) {
      e.printStackTrace(err);
      sink.tryEmit(new HarvestRecord.Failure(ErrorClass.FATAL, e.toString(), null));
      return EXIT_FAILED;
    }
    return sink.isClosed() ? EXIT_FAILED : EXIT_OK;
  }

  private static HarvestRequest readRequest(InputStream in) {
    String line = new BoundedLineReader(in, ProtocolLimits.MAX_ENVELOPE_BYTES).readLine();
    if (line == null) {
      throw new ProtocolViolation("stdin was empty");
    }
    HarvestRequest request = RequestCodec.decode(line);
    if (request.protocol() != HarvestRequest.PROTOCOL_VERSION) {
      throw new ProtocolViolation("unsupported protocol version: " + request.protocol());
    }
    return request;
  }

  /**
   * Writes straight to the stream and never through a {@link PrintStream}, which would swallow the
   * write error that tells a harvester its reader has gone away.
   */
  private static final class StreamSink implements RecordSink {

    private final OutputStream out;
    private boolean closed;

    StreamSink(OutputStream out) {
      this.out = out;
    }

    @Override
    public synchronized void emit(HarvestRecord record) {
      if (closed) {
        throw new SinkClosedException(null);
      }
      byte[] line = (RecordCodec.encode(record) + "\n").getBytes(StandardCharsets.UTF_8);
      try {
        out.write(line, 0, line.length);
        out.flush();
      } catch (IOException e) {
        closed = true;
        throw new SinkClosedException(e);
      }
    }

    synchronized boolean isClosed() {
      return closed;
    }

    void tryEmit(HarvestRecord record) {
      try {
        emit(record);
      } catch (SinkClosedException e) {
        // The failure has nowhere to go; the exit code still reports it.
      }
    }
  }
}
