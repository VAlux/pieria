package dev.alvo.pieria.harvester.protocol;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts records to and from protocol lines, through the JSON tree so no reflection is needed. */
public final class RecordCodec {

  private RecordCodec() {
  }

  /** The record as one line of JSON, with no trailing newline. */
  public static String encode(HarvestRecord record) {
    ObjectNode node = WireJson.MAPPER.createObjectNode();
    switch (record) {
      case HarvestRecord.Document d -> {
        node.put("type", "document").put("id", d.id());
        putIfPresent(node, "version", d.version());
        node.put("title", d.title()).put("url", d.url());
        putIfPresent(node, "author", d.author());
        node.put("updatedAt", d.updatedAt().toString()).put("format", d.format().wire()).put("text", d.text());
        putMetadata(node, d.metadata());
      }
      case HarvestRecord.ConversationThread t -> {
        node.put("type", "thread").put("id", t.id()).put("title", t.title()).put("url", t.url());
        ArrayNode turns = node.putArray("turns");
        for (HarvestRecord.Turn turn : t.turns()) {
          turns.addObject().put("id", turn.id()).put("author", turn.author())
            .put("timestamp", turn.timestamp().toString()).put("text", turn.text());
        }
        putMetadata(node, t.metadata());
      }
      case HarvestRecord.Tombstone t -> node.put("type", "tombstone").put("id", t.id());
      case HarvestRecord.Seen s -> {
        node.put("type", "seen");
        ArrayNode ids = node.putArray("ids");
        s.ids().forEach(ids::add);
      }
      case HarvestRecord.Sweep ignored -> node.put("type", "sweep");
      case HarvestRecord.Checkpoint c -> node.put("type", "checkpoint").set("cursor", c.cursor());
      case HarvestRecord.Progress p -> {
        node.put("type", "progress").put("done", p.done());
        if (p.total() != null) {
          node.put("total", p.total().longValue());
        }
      }
      case HarvestRecord.SecretUpdate s -> node.put("type", "secret_update").put("name", s.name()).put("value", s.value());
      case HarvestRecord.DeviceCode c -> {
        node.put("type", "device_code").put("verificationUri", c.verificationUri()).put("userCode", c.userCode());
        putIfPresent(node, "message", c.message());
        node.put("expiresIn", c.expiresInSeconds());
      }
      case HarvestRecord.Failure f -> {
        node.put("type", "error").put("class", f.errorClass().wire()).put("message", f.message());
        if (f.retryAfterSeconds() != null) {
          node.put("retryAfter", f.retryAfterSeconds().longValue());
        }
      }
    }
    String line = node.toString();
    if (line.getBytes(StandardCharsets.UTF_8).length > ProtocolLimits.MAX_LINE_BYTES) {
      throw new ProtocolViolation("record is over the line limit");
    }
    return line;
  }

  public static HarvestRecord decode(String line) {
    JsonNode node = WireJson.parseObject(line);
    String type = WireJson.requiredString(node, "type");
    return switch (type) {
      case "document" -> new HarvestRecord.Document(
        WireJson.requiredString(node, "id"),
        WireJson.optionalString(node, "version"),
        WireJson.requiredString(node, "title"),
        WireJson.requiredString(node, "url"),
        WireJson.optionalString(node, "author"),
        WireJson.requiredInstant(node, "updatedAt"),
        DocumentFormat.fromWire(WireJson.requiredString(node, "format")),
        WireJson.requiredString(node, "text"),
        WireJson.stringMap(node, "metadata"));
      case "thread" -> new HarvestRecord.ConversationThread(
        WireJson.requiredString(node, "id"),
        WireJson.requiredString(node, "title"),
        WireJson.requiredString(node, "url"),
        turns(WireJson.requiredArray(node, "turns")),
        WireJson.stringMap(node, "metadata"));
      case "tombstone" -> new HarvestRecord.Tombstone(WireJson.requiredString(node, "id"));
      case "seen" -> new HarvestRecord.Seen(strings(WireJson.requiredArray(node, "ids")));
      case "sweep" -> new HarvestRecord.Sweep();
      case "checkpoint" -> new HarvestRecord.Checkpoint(node.get("cursor"));
      case "progress" -> new HarvestRecord.Progress(WireJson.requiredLong(node, "done"), WireJson.optionalLong(node, "total"));
      case "secret_update" -> new HarvestRecord.SecretUpdate(WireJson.requiredString(node, "name"), WireJson.requiredString(node, "value"));
      case "device_code" -> new HarvestRecord.DeviceCode(
        WireJson.requiredString(node, "verificationUri"),
        WireJson.requiredString(node, "userCode"),
        WireJson.optionalString(node, "message"),
        expiresIn(WireJson.requiredLong(node, "expiresIn")));
      case "error" -> new HarvestRecord.Failure(
        ErrorClass.fromWire(WireJson.requiredString(node, "class")),
        WireJson.requiredString(node, "message"),
        WireJson.optionalLong(node, "retryAfter"));
      default -> throw new ProtocolViolation("unknown record type: " + type);
    };
  }

  private static int expiresIn(long seconds) {
    if (seconds < 1 || seconds > ProtocolLimits.MAX_SIGN_IN_SECONDS) {
      throw new ProtocolViolation("device_code expiresIn must be 1.." + ProtocolLimits.MAX_SIGN_IN_SECONDS);
    }
    return (int) seconds;
  }

  private static void putIfPresent(ObjectNode node, String field, String value) {
    if (value != null) {
      node.put(field, value);
    }
  }

  private static void putMetadata(ObjectNode node, Map<String, String> metadata) {
    if (!metadata.isEmpty()) {
      ObjectNode object = node.putObject("metadata");
      metadata.forEach(object::put);
    }
  }

  private static List<HarvestRecord.Turn> turns(JsonNode array) {
    List<HarvestRecord.Turn> turns = new ArrayList<>();
    for (JsonNode turn : array) {
      turns.add(new HarvestRecord.Turn(
        WireJson.requiredString(turn, "id"),
        WireJson.requiredString(turn, "author"),
        WireJson.requiredInstant(turn, "timestamp"),
        WireJson.requiredString(turn, "text")));
    }
    return turns;
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : array) {
      if (!value.isString()) {
        throw new ProtocolViolation("expected a string");
      }
      values.add(value.asString());
    }
    return values;
  }
}
