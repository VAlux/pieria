package dev.alvo.pieria.cli.modules.harness;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.config.toml.ConfigTreeStore;
import dev.alvo.pieria.tools.io.FileOps;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Loads, edits, and saves JSON config files ({@code .mcp.json}, {@code settings.json}) for harnesses
 * that use JSON. Editing is structural and idempotent: unrelated keys are preserved, and re-running
 * yields a single Pieria entry. Used by {@link ClaudeCodeInstaller}.
 */
public final class JsonConfigMerger {

  private static final ObjectMapper MAPPER = JsonMapper.builder()
    .enable(SerializationFeature.INDENT_OUTPUT)
    .build();

  private final ConfigTreeStore store = new ConfigTreeStore(MAPPER);

  /**
   * Read the file as an object, or return a fresh empty object if absent/empty/non-object.
   */
  public ObjectNode load(Path file) throws IOException {
    return store.load(file);
  }

  /**
   * Pretty-print to the file (creating parent dirs), or print the intended content on dry-run.
   */
  public void save(Path file, ObjectNode root, boolean dryRun, Logger log) throws IOException {
    if (dryRun) {
      log.info("  would write {}", file);
      return;
    }
    FileOps.writeFile(file, store.serialize(root) + System.lineSeparator());
    log.info("  wrote {}", file);
  }

  /**
   * Get-or-create a child object field.
   */
  public ObjectNode childObject(ObjectNode parent, String field) {
    return store.childObject(parent, field);
  }

  /**
   * Get-or-create a child array field.
   */
  public ArrayNode childArray(ObjectNode parent, String field) {
    return store.childArray(parent, field);
  }

  public ObjectNode newObject() {
    return store.newObject();
  }

  public ArrayNode newArray() {
    return store.newArray();
  }
}
