package dev.alvo.pieria.harvesters.incremental;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where an incremental harvest got to.
 * <ul>
 *   <li>{@code since}: every change before it, less the overlap, has been reported.</li>
 *   <li>{@code windowStart}: when the window in progress began; null between windows.</li>
 *   <li>{@code lastSweep}: when the last sweep ran.</li>
 *   <li>{@code after}: the last change reported in the window in progress, by (changedAt, id); the
 *   window's changes up to it are done. Null between windows.</li>
 *   <li>{@code recent}: the item states reported near {@code since}, so the overlap costs nothing.</li>
 * </ul>
 */
public final class ChangeCursor {

  public static final int MAX_RECENT = 10_000;
  private static final int VERSION = 1;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private record Reported(String version, Instant changedAt) {
  }

  /** A position in a window's (changedAt, id) order. */
  public record Key(Instant changedAt, String id) implements Comparable<Key> {

    @Override
    public int compareTo(Key other) {
      int byTime = changedAt.compareTo(other.changedAt);
      return byTime != 0 ? byTime : id.compareTo(other.id);
    }
  }

  private final Map<String, Reported> recent = new LinkedHashMap<>();
  private Instant since;
  private Instant windowStart;
  private Instant lastSweep;
  private Key after;

  private ChangeCursor() {
  }

  /** The cursor in {@code node}, or a fresh one when it is missing or unreadable. */
  public static ChangeCursor read(JsonNode node) {
    ChangeCursor cursor = new ChangeCursor();
    if (node == null || !node.isObject() || !node.path("v").isIntegralNumber() || node.path("v").intValue() != VERSION) {
      return cursor;
    }
    try {
      cursor.since = instant(node.get("since"));
      cursor.windowStart = instant(node.get("windowStart"));
      cursor.lastSweep = instant(node.get("lastSweep"));
      JsonNode after = node.get("after");
      if (after != null && !after.isNull()) {
        if (!after.isArray() || after.size() != 2 || !after.get(0).isIntegralNumber() || !after.get(1).isString()) {
          return new ChangeCursor();
        }
        cursor.after = new Key(Instant.ofEpochMilli(after.get(0).longValue()), after.get(1).asString());
      }
      JsonNode recent = node.get("recent");
      if (recent != null && !recent.isNull()) {
        if (!recent.isObject()) {
          return new ChangeCursor();
        }
        for (Map.Entry<String, JsonNode> entry : recent.properties()) {
          JsonNode value = entry.getValue();
          if (!value.isArray() || value.size() != 2 || !value.get(0).isString() || !value.get(1).isIntegralNumber()) {
            return new ChangeCursor();
          }
          cursor.recent.put(entry.getKey(), new Reported(value.get(0).asString(), Instant.ofEpochMilli(value.get(1).longValue())));
        }
      }
      return cursor;
    } catch (DateTimeParseException | IllegalArgumentException e) {
      return new ChangeCursor();
    }
  }

  private static Instant instant(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isString()) {
      throw new IllegalArgumentException("not a time");
    }
    return Instant.parse(node.asString());
  }

  public JsonNode toJson() {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("v", VERSION);
    node.put("since", since == null ? null : since.toString());
    node.put("windowStart", windowStart == null ? null : windowStart.toString());
    node.put("lastSweep", lastSweep == null ? null : lastSweep.toString());
    if (after == null) {
      node.putNull("after");
    } else {
      node.putArray("after").add(after.changedAt().toEpochMilli()).add(after.id());
    }
    ObjectNode entries = node.putObject("recent");
    recent.forEach((id, reported) -> {
      ArrayNode pair = entries.putArray(id);
      pair.add(reported.version());
      pair.add(reported.changedAt().toEpochMilli());
    });
    return node;
  }

  public Instant since() {
    return since;
  }

  public void since(Instant since) {
    this.since = since;
  }

  public Instant windowStart() {
    return windowStart;
  }

  public void windowStart(Instant windowStart) {
    this.windowStart = windowStart;
  }

  public Instant lastSweep() {
    return lastSweep;
  }

  public void lastSweep(Instant lastSweep) {
    this.lastSweep = lastSweep;
  }

  public Key after() {
    return after;
  }

  public void after(Key after) {
    this.after = after;
  }

  /** Whether this exact state of the item has been reported. */
  public boolean reported(String id, String version) {
    Reported reported = recent.get(id);
    return reported != null && reported.version().equals(version);
  }

  public void remember(String id, String version, Instant changedAt) {
    recent.remove(id);
    recent.put(id, new Reported(version, changedAt));
    if (recent.size() > MAX_RECENT) {
      recent.entrySet().stream().min(Comparator.comparing(entry -> entry.getValue().changedAt()))
        .map(Map.Entry::getKey).ifPresent(recent::remove);
    }
  }

  /** Forgets the states that changed before {@code before}. */
  public void prune(Instant before) {
    recent.values().removeIf(reported -> reported.changedAt().isBefore(before));
  }
}
