package dev.alvo.pieria.harvest;

import dev.alvo.pieria.harvester.manifest.ConfigFieldSpec;
import dev.alvo.pieria.harvester.manifest.FieldKind;
import dev.alvo.pieria.harvester.manifest.HarvesterManifest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Checks a source's settings against what its harvester's manifest declares. */
final class SourceConfigValidator {

  private SourceConfigValidator() {
  }

  /**
   * @param secretNames the secrets being supplied, or already stored
   * @param requireAll  whether required settings must be present, which a partial change doesn't need
   */
  static void validate(HarvesterManifest manifest, ObjectNode config, Set<String> secretNames, boolean requireAll) {
    Map<String, ConfigFieldSpec> fields = manifest.config().stream()
      .collect(Collectors.toMap(ConfigFieldSpec::key, Function.identity()));

    for (Map.Entry<String, JsonNode> entry : config.properties()) {
      ConfigFieldSpec field = fields.get(entry.getKey());
      if (field == null) {
        throw new IllegalArgumentException("unknown setting: " + entry.getKey());
      }
      if (field.kind() == FieldKind.SECRET) {
        throw new IllegalArgumentException("setting " + entry.getKey() + " is a secret and can't be stored in config");
      }
      requireType(field, entry.getValue());
    }
    for (String name : secretNames) {
      ConfigFieldSpec field = fields.get(name);
      if (field == null || field.kind() != FieldKind.SECRET) {
        throw new IllegalArgumentException("unknown secret: " + name);
      }
    }
    if (requireAll) {
      for (ConfigFieldSpec field : manifest.config()) {
        boolean present = field.kind() == FieldKind.SECRET ? secretNames.contains(field.key()) : config.has(field.key());
        if (field.required() && !present) {
          throw new IllegalArgumentException("missing required setting: " + field.key());
        }
      }
    }
  }

  private static void requireType(ConfigFieldSpec field, JsonNode value) {
    boolean valid = switch (field.kind()) {
      case STRING -> value.isString();
      case INT -> value.isIntegralNumber();
      case BOOL -> value.isBoolean();
      case ENUM -> value.isString() && field.options().contains(value.asString());
      case LIST -> isStringList(value);
      case SECRET -> false;
    };
    if (!valid) {
      throw new IllegalArgumentException("setting " + field.key() + " must be of kind " + field.kind().wire());
    }
  }

  private static boolean isStringList(JsonNode value) {
    if (!value.isArray()) {
      return false;
    }
    for (JsonNode item : value) {
      if (!item.isString()) {
        return false;
      }
    }
    return true;
  }
}
