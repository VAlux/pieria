package dev.alvo.pieria.harvester.protocol;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strictly typed reads from the JSON tree. Numbers never coerce to strings, or the reverse. */
final class WireJson {

  static final ObjectMapper MAPPER = new ObjectMapper();

  private WireJson() {
  }

  static JsonNode parseObject(String line) {
    JsonNode root;
    try {
      root = MAPPER.readTree(line);
    } catch (JacksonException e) {
      throw new ProtocolViolation("not valid JSON", e);
    }
    if (root == null || !root.isObject()) {
      throw new ProtocolViolation("expected a JSON object");
    }
    return root;
  }

  static String requiredString(JsonNode node, String field) {
    String value = optionalString(node, field);
    if (value == null) {
      throw new ProtocolViolation("missing field: " + field);
    }
    return value;
  }

  static String optionalString(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw new ProtocolViolation("field must be a string: " + field);
    }
    return value.asString();
  }

  static Instant requiredInstant(JsonNode node, String field) {
    String value = requiredString(node, field);
    try {
      return Instant.parse(value);
    } catch (DateTimeException e) {
      throw new ProtocolViolation("field is not an ISO-8601 instant: " + field, e);
    }
  }

  static Long optionalLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new ProtocolViolation("field must be an integer: " + field);
    }
    return value.longValue();
  }

  static long requiredLong(JsonNode node, String field) {
    Long value = optionalLong(node, field);
    if (value == null) {
      throw new ProtocolViolation("missing field: " + field);
    }
    return value;
  }

  static JsonNode requiredArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray()) {
      throw new ProtocolViolation("field must be an array: " + field);
    }
    return value;
  }

  static Map<String, String> stringMap(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return Map.of();
    }
    if (!value.isObject()) {
      throw new ProtocolViolation("field must be an object: " + field);
    }
    Map<String, String> map = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : value.properties()) {
      if (!entry.getValue().isString()) {
        throw new ProtocolViolation("values must be strings: " + field);
      }
      map.put(entry.getKey(), entry.getValue().asString());
    }
    return map;
  }
}
