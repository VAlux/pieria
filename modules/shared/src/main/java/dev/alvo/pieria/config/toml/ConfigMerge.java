package dev.alvo.pieria.config.toml;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deep-merge for layered Pieria config trees. Objects merge recursively; scalars and arrays in
 * the overlay replace the base value wholesale (a project that sets {@code skip-dirs} states the
 * full list, it does not append). Inputs are never mutated.
 */
public final class ConfigMerge {

  private ConfigMerge() {
  }

  /**
   * Overlay {@code overlay} onto {@code base}. A {@code null}/missing overlay returns {@code base}
   * (and vice versa); when either side of a key is not an object, the overlay value wins.
   */
  public static JsonNode deepMerge(JsonNode base, JsonNode overlay) {
    if (overlay == null || overlay.isNull() || overlay.isMissingNode()) {
      return base;
    }
    if (base == null || base.isNull() || base.isMissingNode()) {
      return overlay.deepCopy();
    }
    if (!(base instanceof ObjectNode baseObject) || !(overlay instanceof ObjectNode overlayObject)) {
      return overlay.deepCopy();
    }
    ObjectNode merged = baseObject.deepCopy();
    for (var property : overlayObject.properties()) {
      JsonNode existing = merged.get(property.getKey());
      merged.set(property.getKey(), deepMerge(existing, property.getValue()));
    }
    return merged;
  }

  /**
   * Merge layers lowest-precedence first: {@code mergeAll(defaults, global, project)} yields
   * project &gt; global &gt; defaults.
   */
  public static JsonNode mergeAll(JsonNode... layers) {
    JsonNode result = null;
    for (JsonNode layer : layers) {
      result = deepMerge(result, layer);
    }
    return result;
  }
}
