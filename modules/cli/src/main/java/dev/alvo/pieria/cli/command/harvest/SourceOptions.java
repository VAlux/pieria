package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.api.response.HarvesterResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns command-line settings into what the daemon expects, using the harvester's declared fields. */
final class SourceOptions {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SourceOptions() {
  }

  /** Seconds from {@code 90}, {@code 30m}, {@code 6h} or {@code 1d}. */
  static long parseInterval(String value) {
    if (!value.matches("\\d+[mhd]?")) {
      throw new IllegalArgumentException("invalid interval: " + value + " (use a form like 90, 30m, 6h or 1d)");
    }
    char unit = value.charAt(value.length() - 1);
    if (Character.isDigit(unit)) {
      return Long.parseLong(value);
    }
    long amount = Long.parseLong(value.substring(0, value.length() - 1));
    return switch (unit) {
      case 'm' -> amount * 60;
      case 'h' -> amount * 3_600;
      default -> amount * 86_400;
    };
  }

  static ObjectNode parseConfig(List<String> settings, HarvesterResponse harvester) {
    ObjectNode config = MAPPER.createObjectNode();
    for (String setting : settings) {
      int separator = setting.indexOf('=');
      if (separator < 1) {
        throw new IllegalArgumentException("settings must look like key=value: " + setting);
      }
      String key = setting.substring(0, separator);
      HarvesterResponse.Field field = harvester.fields().stream().filter(candidate -> candidate.key().equals(key)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown setting: " + key));
      config.set(key, value(field, setting.substring(separator + 1)));
    }
    return config;
  }

  static JsonNode value(HarvesterResponse.Field field, String raw) {
    return switch (field.kind()) {
      case "secret" -> throw new IllegalArgumentException(field.key() + " is a secret: use --secret-env " + field.key() + "=ENV_VAR");
      case "int" -> integer(field, raw);
      case "bool" -> bool(field, raw);
      case "list" -> list(raw);
      default -> MAPPER.getNodeFactory().stringNode(raw);
    };
  }

  private static JsonNode integer(HarvesterResponse.Field field, String raw) {
    try {
      return MAPPER.getNodeFactory().numberNode(Long.parseLong(raw.strip()));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(field.key() + " must be a whole number: " + raw);
    }
  }

  private static JsonNode bool(HarvesterResponse.Field field, String raw) {
    if (!raw.equals("true") && !raw.equals("false")) {
      throw new IllegalArgumentException(field.key() + " must be true or false: " + raw);
    }
    return MAPPER.getNodeFactory().booleanNode(raw.equals("true"));
  }

  private static JsonNode list(String raw) {
    ArrayNode values = MAPPER.createArrayNode();
    for (String item : raw.split(",")) {
      if (!item.isBlank()) {
        values.add(item.strip());
      }
    }
    return values;
  }

  /** Reads secrets from {@code name=ENV_VAR} pairs. Error messages name the variable, never its value. */
  static Map<String, String> secretsFromEnvironment(List<String> specs) {
    Map<String, String> secrets = new LinkedHashMap<>();
    for (String spec : specs) {
      int separator = spec.indexOf('=');
      if (separator < 1 || separator == spec.length() - 1) {
        throw new IllegalArgumentException("--secret-env must look like name=ENV_VAR: " + spec);
      }
      String variable = spec.substring(separator + 1);
      String value = System.getenv(variable);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("environment variable " + variable + " is not set");
      }
      secrets.put(spec.substring(0, separator), value);
    }
    return secrets;
  }
}
