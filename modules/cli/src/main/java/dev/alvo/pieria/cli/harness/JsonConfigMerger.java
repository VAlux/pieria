package dev.alvo.pieria.cli.harness;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads, edits, and saves JSON config files ({@code .mcp.json}, {@code settings.json}) for harnesses
 * that use JSON. Editing is structural and idempotent: unrelated keys are preserved, and re-running
 * yields a single Pieria entry. Used by {@link ClaudeCodeInstaller}.
 */
public final class JsonConfigMerger {

  private final ObjectMapper mapper = JsonMapper.builder()
    .enable(SerializationFeature.INDENT_OUTPUT)
    .build();

  /** Read the file as an object, or return a fresh empty object if absent/empty/non-object. */
  public ObjectNode load(Path file) throws IOException {
    if (Files.exists(file) && Files.size(file) > 0) {
      JsonNode node = mapper.readTree(Files.readAllBytes(file));
      if (node instanceof ObjectNode object) {
        return object;
      }
    }
    return mapper.createObjectNode();
  }

  /** Pretty-print to the file (creating parent dirs), or print the intended content on dry-run. */
  public void save(Path file, ObjectNode root, boolean dryRun, PrintStream out) throws IOException {
    String content = mapper.writeValueAsString(root) + System.lineSeparator();
    if (dryRun) {
      out.printf("  would write %s%n", file);
      return;
    }
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    out.printf("  wrote %s%n", file);
  }

  /** Get-or-create a child object field. */
  public ObjectNode childObject(ObjectNode parent, String field) {
    JsonNode existing = parent.get(field);
    if (existing instanceof ObjectNode object) {
      return object;
    }
    ObjectNode created = mapper.createObjectNode();
    parent.set(field, created);
    return created;
  }

  /** Get-or-create a child array field. */
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
