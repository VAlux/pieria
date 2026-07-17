package dev.alvo.pieria.config.toml;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generic load/edit/serialize helper over a Jackson {@code ObjectNode} tree, parameterized by the
 * target (de)serializer (TOML, plain JSON, pretty-printed JSON, ...). Editing is structural
 * get-or-create: unrelated keys are preserved across a load-edit-save round trip.
 */
public final class ConfigTreeStore {

  private final ObjectMapper mapper;

  public ConfigTreeStore(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Read the file as an object tree, or return a fresh empty object when absent/empty/non-object.
   */
  public ObjectNode load(Path file) throws IOException {
    if (Files.exists(file) && Files.size(file) > 0) {
      JsonNode node = mapper.readTree(Files.readAllBytes(file));
      if (node instanceof ObjectNode object) {
        return object;
      }
    }
    return mapper.createObjectNode();
  }

  public String serialize(ObjectNode root) {
    return mapper.writeValueAsString(root);
  }

  /**
   * Get-or-create a child object field.
   */
  public ObjectNode childObject(ObjectNode parent, String field) {
    JsonNode existing = parent.get(field);
    if (existing instanceof ObjectNode object) {
      return object;
    }
    ObjectNode created = mapper.createObjectNode();
    parent.set(field, created);
    return created;
  }

  /**
   * Get-or-create a child array field.
   */
  public ArrayNode childArray(ObjectNode parent, String field) {
    JsonNode existing = parent.get(field);
    if (existing instanceof ArrayNode array) {
      return array;
    }
    ArrayNode created = mapper.createArrayNode();
    parent.set(field, created);
    return created;
  }

  public ObjectNode newObject() {
    return mapper.createObjectNode();
  }

  public ArrayNode newArray() {
    return mapper.createArrayNode();
  }
}
