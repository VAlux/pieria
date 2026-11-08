package dev.alvo.pieria.domain.memory;

import dev.alvo.pieria.api.response.MemoryOrigin;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * Reads and writes the {@code origin} object in a memory's payload. The payload is the single source
 * of truth for where a memory came from; the {@code memories.origin_source_id} column is generated
 * from the {@code source_id} field written here.
 */
public final class MemoryOrigins {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String ORIGIN = "origin";
  private static final String SOURCE_ID = "source_id";
  private static final String HARVESTER = "harvester";
  private static final String EXTERNAL_ID = "external_id";
  private static final String URL = "url";
  private static final String TRUST = "trust";

  private MemoryOrigins() {
  }

  /**
   * The origin recorded in {@code payload}, or empty when it has none or is not a JSON object.
   */
  public static Optional<MemoryOrigin> parse(String payload) {
    JsonNode origin = readObject(payload).path(ORIGIN);
    if (!origin.isObject() || !origin.path(SOURCE_ID).isString()) {
      return Optional.empty();
    }
    return Optional.of(new MemoryOrigin(
      origin.path(SOURCE_ID).asString(),
      text(origin, HARVESTER),
      text(origin, EXTERNAL_ID),
      text(origin, URL),
      text(origin, TRUST)));
  }

  /**
   * Returns {@code payload} with its origin set to {@code origin}, overwriting any origin already
   * there: the extractor's payload is model output and must not be able to claim its own provenance.
   * A payload that is not a JSON object is replaced, because a harvested memory without an origin
   * would lose its untrusted label.
   */
  public static String withOrigin(String payload, IngestOrigin origin) {
    ObjectNode object = readObject(payload);
    ObjectNode stamped = object.putObject(ORIGIN);
    stamped.put(SOURCE_ID, origin.sourceId());
    stamped.put(HARVESTER, origin.harvester());
    stamped.put(EXTERNAL_ID, origin.externalId());
    if (origin.url() != null) {
      stamped.put(URL, origin.url());
    }
    stamped.put(TRUST, MemoryOrigin.UNTRUSTED);
    return object.toString();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isString() ? value.asString() : null;
  }

  private static ObjectNode readObject(String payload) {
    if (payload != null && !payload.isBlank()) {
      try {
        if (MAPPER.readTree(payload) instanceof ObjectNode object) {
          return object;
        }
      } catch (RuntimeException e) {
        // Opaque or malformed payloads are legal; they simply carry no fields.
      }
    }
    return MAPPER.createObjectNode();
  }
}
