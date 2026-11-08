package dev.alvo.pieria.harvester.manifest;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * One setting a harvester asks for.
 *
 * @param hint         help text; may be null
 * @param options      permitted values, when {@code kind} is {@code enum}
 * @param defaultValue the default; may be null
 */
public record ConfigFieldSpec(String key, FieldKind kind, String label, String hint, boolean required,
                              List<String> options, JsonNode defaultValue) {

  public ConfigFieldSpec {
    options = options == null ? List.of() : List.copyOf(options);
  }
}
