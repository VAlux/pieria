package dev.alvo.pieria.harvesters;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a source's settings. The daemon passes config through as the user gave it, without applying
 * defaults, so every harvester validates its own. A missing or {@code null} value counts as absent.
 */
public final class ConfigValues {

  private ConfigValues() {
  }

  public static HarvesterException invalid(String message) {
    return new HarvesterException(ErrorClass.CONFIG, message);
  }

  private static JsonNode value(ObjectNode config, String key) {
    JsonNode node = config.get(key);
    return node == null || node.isNull() ? null : node;
  }

  /** A list of strings; empty when absent. */
  public static List<String> strings(ObjectNode config, String key) throws HarvesterException {
    JsonNode node = value(config, key);
    if (node == null) {
      return List.of();
    }
    if (!node.isArray()) {
      throw invalid(key + " must be a list");
    }
    List<String> values = new ArrayList<>();
    for (JsonNode element : node) {
      if (!element.isString()) {
        throw invalid(key + " must be a list of strings");
      }
      values.add(element.asString());
    }
    return List.copyOf(values);
  }

  /** The stripped value, or null when absent or blank. */
  public static String string(ObjectNode config, String key) throws HarvesterException {
    JsonNode node = value(config, key);
    if (node == null) {
      return null;
    }
    if (!node.isString()) {
      throw invalid(key + " must be text");
    }
    String stripped = node.asString().strip();
    return stripped.isEmpty() ? null : stripped;
  }

  public static String requiredString(ObjectNode config, String key) throws HarvesterException {
    String value = string(config, key);
    if (value == null) {
      throw invalid(key + " is required");
    }
    return value;
  }

  public static int integer(ObjectNode config, String key, int fallback, int min, int max) throws HarvesterException {
    JsonNode node = value(config, key);
    if (node == null) {
      return fallback;
    }
    if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < min || node.intValue() > max) {
      throw invalid(key + " must be a whole number from " + min + " to " + max);
    }
    return node.intValue();
  }

  public static boolean flag(ObjectNode config, String key) throws HarvesterException {
    JsonNode node = value(config, key);
    if (node == null) {
      return false;
    }
    if (!node.isBoolean()) {
      throw invalid(key + " must be true or false");
    }
    return node.booleanValue();
  }

  /** One of {@code options}, compared and returned lowercased. */
  public static String choice(ObjectNode config, String key, String fallback, List<String> options) throws HarvesterException {
    String value = string(config, key);
    if (value == null) {
      return fallback;
    }
    String lowered = value.toLowerCase(Locale.ROOT);
    if (!options.contains(lowered)) {
      throw invalid(key + " must be one of " + String.join(", ", options));
    }
    return lowered;
  }

  public static String requiredSecret(Map<String, String> secrets, String key) throws HarvesterException {
    String value = secrets.get(key);
    if (value == null || value.isBlank()) {
      throw invalid("the " + key + " secret is required");
    }
    return value;
  }
}
