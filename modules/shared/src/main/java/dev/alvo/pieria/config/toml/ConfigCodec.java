package dev.alvo.pieria.config.toml;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Single (de)serialization point for Pieria config trees. Configured with kebab-case naming so
 * record components ({@code maxFileBytes}, {@code rrfK}) map to the same keys everywhere:
 * TOML files ({@code max-file-bytes}), the persisted per-profile JSON, and the
 * {@code /v1/profiles/{name}/config} wire format — all matching the Spring relaxed-binding
 * names in {@code pieria.properties}.
 */
public final class ConfigCodec {

  private static final JsonMapper MAPPER = JsonMapper.builder()
    .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
    .build();

  private ConfigCodec() {
  }

  /** Bind a (merged) tree to a config record; {@code null} binds to the type's empty shape. */
  public static <T> T bind(JsonNode node, Class<T> type) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      node = MAPPER.createObjectNode();
    }
    return MAPPER.treeToValue(node, type);
  }

  public static JsonNode toNode(Object value) {
    return MAPPER.valueToTree(value);
  }

  public static String toJson(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  public static JsonNode parseJson(String json) {
    return MAPPER.readTree(json);
  }
}
