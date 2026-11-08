package dev.alvo.pieria.harvester;

import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;
import dev.alvo.pieria.harvester.protocol.RecordCodec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HarvesterRunnerTests {

  private static final String ENVELOPE = "{\"protocol\":1,\"config\":{},\"secrets\":{}}\n";

  @FunctionalInterface
  private interface Work {
    void run(RecordSink sink) throws HarvesterException;
  }

  private static Harvester harvesting(Work work) {
    return new Harvester() {
      @Override
      public void check(HarvestRequest request) throws HarvesterException {
        work.run(null);
      }

      @Override
      public void harvest(HarvestRequest request, RecordSink sink) throws HarvesterException {
        work.run(sink);
      }
    };
  }

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private int run(Harvester harvester, String envelope, OutputStream output, String... args) {
    return HarvesterRunner.run(harvester, args, new ByteArrayInputStream(envelope.getBytes(StandardCharsets.UTF_8)),
      output, new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  private int run(Harvester harvester, String... args) {
    return run(harvester, ENVELOPE, out, args);
  }

  private List<HarvestRecord> records() {
    String text = out.toString(StandardCharsets.UTF_8);
    return text.isEmpty() ? List.of() : Arrays.stream(text.split("\n")).map(RecordCodec::decode).toList();
  }

  @Test
  void harvestWritesRecordsAndExitsZero() {
    int exit = run(harvesting(sink -> sink.emit(new HarvestRecord.Sweep())), "harvest");

    assertThat(exit).isEqualTo(HarvesterRunner.EXIT_OK);
    assertThat(records()).containsExactly(new HarvestRecord.Sweep());
  }

  @Test
  void checkExitsZeroAndWritesNothing() {
    assertThat(run(harvesting(sink -> { }), "check")).isEqualTo(HarvesterRunner.EXIT_OK);
    assertThat(records()).isEmpty();
  }

  @Test
  void harvesterExceptionBecomesAnErrorRecord() {
    int exit = run(harvesting(sink -> {
      throw new HarvesterException(ErrorClass.RATE_LIMITED, "slow", 30L);
    }), "harvest");

    assertThat(exit).isEqualTo(HarvesterRunner.EXIT_FAILED);
    assertThat(records()).containsExactly(new HarvestRecord.Failure(ErrorClass.RATE_LIMITED, "slow", 30L));
  }

  @Test
  void checkFailureBecomesAnErrorRecord() {
    int exit = run(harvesting(sink -> {
      throw new HarvesterException(ErrorClass.AUTH, "bad token");
    }), "check");

    assertThat(exit).isEqualTo(HarvesterRunner.EXIT_FAILED);
    assertThat(records()).containsExactly(new HarvestRecord.Failure(ErrorClass.AUTH, "bad token", null));
  }

  @Test
  void unexpectedExceptionBecomesFatal() {
    int exit = run(harvesting(sink -> {
      throw new IllegalStateException("boom");
    }), "harvest");

    assertThat(exit).isEqualTo(HarvesterRunner.EXIT_FAILED);
    assertThat(((HarvestRecord.Failure) records().getFirst()).errorClass()).isEqualTo(ErrorClass.FATAL);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("IllegalStateException");
  }

  @Test
  void invalidRecordFromAHarvesterIsFatal() {
    int exit = run(harvesting(sink -> sink.emit(new HarvestRecord.Tombstone(" "))), "harvest");

    assertThat(exit).isEqualTo(HarvesterRunner.EXIT_FAILED);
    assertThat(((HarvestRecord.Failure) records().getFirst()).errorClass()).isEqualTo(ErrorClass.FATAL);
  }

  @Test
  void unknownCommandIsAUsageError() {
    assertThat(run(harvesting(sink -> { }), "nope")).isEqualTo(HarvesterRunner.EXIT_USAGE);
    assertThat(records()).isEmpty();
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("usage");
  }

  @Test
  void missingCommandIsAUsageError() {
    assertThat(run(harvesting(sink -> { }))).isEqualTo(HarvesterRunner.EXIT_USAGE);
  }

  private void assertConfigError(String envelope) {
    assertThat(run(harvesting(sink -> { }), envelope, out, "harvest")).isEqualTo(HarvesterRunner.EXIT_USAGE);
    assertThat(((HarvestRecord.Failure) records().getFirst()).errorClass()).isEqualTo(ErrorClass.CONFIG);
  }

  @Test
  void badEnvelopeIsAConfigError() {
    assertConfigError("garbage\n");
  }

  @Test
  void emptyStdinIsAConfigError() {
    assertConfigError("");
  }

  @Test
  void unsupportedProtocolIsAConfigError() {
    assertConfigError("{\"protocol\":2,\"config\":{},\"secrets\":{}}\n");
  }

  @Test
  void oversizeEnvelopeIsAConfigError() {
    assertConfigError("x".repeat(ProtocolLimits.MAX_ENVELOPE_BYTES + 2) + "\n");
  }

  @Test
  void eachRecordIsFlushed() {
    int[] flushes = {0};
    OutputStream counting = new ByteArrayOutputStream() {
      @Override
      public void flush() {
        flushes[0]++;
      }
    };

    run(harvesting(sink -> {
      sink.emit(new HarvestRecord.Sweep());
      sink.emit(new HarvestRecord.Sweep());
    }), ENVELOPE, counting, "harvest");

    assertThat(flushes[0]).isGreaterThanOrEqualTo(2);
  }

  private static final class ClosedOutput extends OutputStream {
    int writes;

    @Override
    public void write(int b) throws IOException {
      writes++;
      throw new IOException("closed");
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      writes++;
      throw new IOException("closed");
    }
  }

  @Test
  void closedOutputStopsTheHarvester() {
    ClosedOutput closed = new ClosedOutput();

    int exit = run(harvesting(sink -> {
      while (true) {
        sink.emit(new HarvestRecord.Sweep());
      }
    }), ENVELOPE, closed, "harvest");

    assertThat(exit).isEqualTo(HarvesterRunner.EXIT_FAILED);
    assertThat(closed.writes).isEqualTo(1);
  }

  @Test
  void catchingSinkClosedStillExitsFailed() {
    int exit = run(harvesting(sink -> {
      try {
        sink.emit(new HarvestRecord.Sweep());
      } catch (SinkClosedException ignored) {
        // A harvester that swallows this must not be able to report success.
      }
    }), ENVELOPE, new ClosedOutput(), "harvest");

    assertThat(exit).isEqualTo(HarvesterRunner.EXIT_FAILED);
  }

  @Test
  void emitAfterCloseDoesNotWriteAgain() {
    ClosedOutput closed = new ClosedOutput();

    run(harvesting(sink -> {
      for (int i = 0; i < 3; i++) {
        try {
          sink.emit(new HarvestRecord.Sweep());
        } catch (SinkClosedException ignored) {
          // Keep trying, as a careless harvester would.
        }
      }
    }), ENVELOPE, closed, "harvest");

    assertThat(closed.writes).isEqualTo(1);
  }
}
