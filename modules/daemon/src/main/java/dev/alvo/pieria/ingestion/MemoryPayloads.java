package dev.alvo.pieria.ingestion;

import dev.alvo.pieria.domain.memory.MemoryTimes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * Payload edits applied deterministically in Java on the way to the store.
 *
 * <p>These fields are never asked of the extraction model. A model asked "when did this happen"
 * would be doing date arithmetic, which is exactly what the pipeline computes in code instead — and
 * it would be guessing from text the normalizer has already rewritten.
 */
final class MemoryPayloads {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private MemoryPayloads() {
  }

  /**
   * Returns {@code payload} with {@link MemoryTimes#STATED_AT} set to {@code statedAt}.
   *
   * <p>Deliberately <em>not</em> {@code occurred_at}: that field means when an event actually
   * happened, which is frequently not when it was mentioned ("I ran the race last Saturday"). Writing
   * the speaking time there would corrupt the event-date arithmetic that already reads it.
   *
   * <p>A payload that is absent, blank, or not a JSON object is returned unchanged rather than
   * replaced — a malformed payload is a separate problem, and discarding it here would lose data.
   */
  static String withStatedAt(String payload, Instant statedAt) {
    if (statedAt == null) {
      return payload;
    }
    JsonNode parsed = parse(payload == null || payload.isBlank() ? "{}" : payload);
    if (parsed == null || !parsed.isObject()) {
      return payload;
    }
    ObjectNode object = (ObjectNode) parsed;
    object.put(MemoryTimes.STATED_AT, statedAt.toString());
    return object.toString();
  }

  private static JsonNode parse(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
