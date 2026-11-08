package dev.alvo.pieria.harvester.conformance;

import dev.alvo.pieria.harvester.Harvester;
import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.HarvesterRunner;
import dev.alvo.pieria.harvester.RecordSink;
import dev.alvo.pieria.harvester.SinkClosedException;
import dev.alvo.pieria.harvester.protocol.DocumentFormat;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import tools.jackson.databind.ObjectMapper;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A real child process for the kit to test. {@code ok} is a correct incremental harvester of three
 * documents, and every other mode changes one behaviour.
 */
public final class FixtureHarvester implements Harvester {

  private static final int ITEMS = 3;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String mode;

  private FixtureHarvester(String mode) {
    this.mode = mode;
  }

  public static void main(String[] args) {
    HarvesterRunner.main(new FixtureHarvester(args[0]), Arrays.copyOfRange(args, 1, args.length));
  }

  @Override
  public void check(HarvestRequest request, RecordSink sink) throws HarvesterException {
    if (mode.equals("check-emits-items")) {
      sink.emit(new HarvestRecord.Sweep());
    }
    if (!mode.equals("accepts-anything") && !request.config().path("valid").asBoolean()) {
      throw new HarvesterException(ErrorClass.AUTH, "bad credentials");
    }
  }

  private static HarvestRecord.Document document(String id) {
    return new HarvestRecord.Document(id, null, "Title " + id, "https://x/" + id, null,
      Instant.parse("2026-01-01T00:00:00Z"), DocumentFormat.TEXT, "text of " + id, Map.of());
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void hangForever() {
    while (true) {
      pause(1_000);
    }
  }

  @Override
  public void harvest(HarvestRequest request, RecordSink sink) throws HarvesterException {
    switch (mode) {
      case "crash" -> throw new IllegalStateException("boom");
      case "hangs" -> hangForever();
      case "no-items" -> {
        sink.emit(new HarvestRecord.Checkpoint(MAPPER.createObjectNode().put("next", 0)));
        return;
      }
      case "garbage-stdout" -> {
        try (FileOutputStream raw = new FileOutputStream(FileDescriptor.out)) {
          raw.write("garbage\n".getBytes());
        } catch (IOException e) {
          throw new HarvesterException(ErrorClass.FATAL, e.toString());
        }
        return;
      }
      case "noisy" -> System.out.println("a library printing to stdout");
      case "leaks-secret" -> System.err.println("using " + request.secrets().get("token"));
      case "sweep-first" -> {
        if (request.cursor() == null) {
          sink.emit(new HarvestRecord.Sweep());
        }
      }
      case "orphan-holds-stdout" -> {
        try {
          new ProcessBuilder("sleep", "15").inheritIO().start();
        } catch (IOException e) {
          throw new HarvesterException(ErrorClass.FATAL, e.toString());
        }
        return;
      }
      default -> { }
    }

    boolean resumed = request.cursor() != null;
    int start = resumed ? request.cursor().path("next").asInt() : 0;
    if (mode.equals("loses-on-resume") && resumed) {
      start++;
    }
    if (mode.equals("crashes-on-end-cursor") && start == ITEMS) {
      throw new IllegalStateException("nothing past the end");
    }
    boolean limitedRun = request.limits().maxItemsPerRun() != null;
    if (mode.equals("limited-no-items") && limitedRun) {
      return;
    }
    Integer maxItems = mode.equals("ignores-limit") ? null : request.limits().maxItemsPerRun();
    Long maxChars = mode.equals("ignores-char-limit") ? null : request.limits().maxCharsPerRun();
    if (mode.equals("full-pass-on-resume") && start > 0) {
      List<String> earlier = new ArrayList<>();
      for (int i = 0; i < start; i++) {
        earlier.add("item-" + i);
      }
      sink.emit(new HarvestRecord.Seen(earlier));
    }

    int emitted = 0;
    long chars = 0;
    boolean limited = false;
    for (int i = start; i < ITEMS; i++) {
      long size = document("item-" + i).text().length();
      if ((maxItems != null && emitted == maxItems)
        || (maxChars != null && emitted > 0 && chars + size > maxChars)) {
        limited = true;
        break;
      }
      chars += size;
      if (mode.equals("slow-ok")) {
        pause(300);
      }
      String id = mode.equals("unstable-ids") ? UUID.randomUUID().toString() : "item-" + i;
      emit(sink, document(id));
      emitted++;
      if (!mode.equals("no-checkpoints") && !(mode.equals("limited-no-checkpoint") && limitedRun)
        && !(mode.equals("limited-stalls") && limitedRun && resumed)) {
        emit(sink, new HarvestRecord.Checkpoint(MAPPER.createObjectNode().put("next", i + 1)));
      }
    }

    if (mode.equals("undeclared-tombstone")) {
      sink.emit(new HarvestRecord.Tombstone("item-0"));
      return;
    }
    boolean sweepAllowed = (!resumed || mode.equals("full-pass-on-resume")) && !limited && !mode.equals("sweep-first");
    if (sweepAllowed || mode.equals("sweeps-on-resume") || mode.equals("sweeps-when-limited")) {
      sink.emit(new HarvestRecord.Sweep());
    }
  }

  private void emit(RecordSink sink, HarvestRecord record) {
    if (!mode.equals("ignores-closed-stdout")) {
      sink.emit(record);
      return;
    }
    pause(300);
    try {
      sink.emit(record);
    } catch (SinkClosedException e) {
      hangForever();
    }
  }
}
