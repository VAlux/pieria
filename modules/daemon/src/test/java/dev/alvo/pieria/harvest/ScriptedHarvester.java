package dev.alvo.pieria.harvest;

import dev.alvo.pieria.harvester.Harvester;
import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.HarvesterRunner;
import dev.alvo.pieria.harvester.RecordSink;
import dev.alvo.pieria.harvester.protocol.DocumentFormat;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.RecordCodec;
import tools.jackson.databind.JsonNode;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/** A real child process that plays back what its config tells it to. */
public final class ScriptedHarvester implements Harvester {

  public static void main(String[] args) {
    HarvesterRunner.main(new ScriptedHarvester(), args);
  }

  @Override
  public void check(HarvestRequest request, RecordSink sink) {
  }

  private static void echo(RecordSink sink, String id, String text) {
    sink.emit(new HarvestRecord.Document(id, null, "Echo", "https://x/" + id, null, Instant.parse("2026-03-01T00:00:00Z"),
      DocumentFormat.TEXT, text, Map.of()));
  }

  @Override
  public void harvest(HarvestRequest request, RecordSink sink) throws HarvesterException {
    JsonNode config = request.config();
    try {
      if (config.has("pidFile")) {
        Files.writeString(Path.of(config.get("pidFile").asString()), Long.toString(ProcessHandle.current().pid()));
      }
      if (config.has("stderr")) {
        System.err.println(config.get("stderr").asString());
      }
      if (config.has("rawLine")) {
        new FileOutputStream(FileDescriptor.out).write((config.get("rawLine").asString() + "\n").getBytes());
      }
    } catch (IOException e) {
      throw new HarvesterException(ErrorClass.FATAL, e.toString());
    }
    if (config.path("echoCursor").asBoolean()) {
      echo(sink, "cursor", "cursor was " + request.cursor());
    }
    if (config.path("echoSecrets").asBoolean()) {
      echo(sink, "secrets", "secrets were " + request.secrets().keySet());
    }
    for (JsonNode record : config.path("records")) {
      sink.emit(RecordCodec.decode(record.toString()));
    }
    if (config.has("sleepMs")) {
      try {
        Thread.sleep(config.get("sleepMs").asLong());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (config.has("exit")) {
      Runtime.getRuntime().halt(config.get("exit").asInt());
    }
  }
}
