package dev.alvo.pieria.cli.modules.harness;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads, edits, and saves TOML config files ({@code config.toml}) for harnesses that use TOML.
 * Editing is structural and idempotent. Used by {@link CodexInstaller}. The tree model is the same
 * {@code JsonNode}/{@code ObjectNode} API as JSON; only the (de)serializer differs.
 */
public final class TomlConfigMerger {

  private final TomlMapper mapper = TomlMapper.builder().build();

  /**
   * Read the file as an object, or return a fresh empty object if absent/empty/non-object.
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

  /**
   * Serialize to TOML and write (creating parent dirs), or print the intended target on dry-run.
   */
  public void save(Path file, ObjectNode root, boolean dryRun, PrintStream out) throws IOException {
    String content = mapper.writeValueAsString(root);
    if (dryRun) {
      out.printf("  would write %s%n", file);
      return;
    }
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    out.printf("  wrote %s%n", file);
  }

  public ObjectNode childObject(ObjectNode parent, String field) {
    JsonNode existing = parent.get(field);
    if (existing instanceof ObjectNode object) {
      return object;
    }
    ObjectNode created = mapper.createObjectNode();
    parent.set(field, created);
    return created;
  }

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
}
