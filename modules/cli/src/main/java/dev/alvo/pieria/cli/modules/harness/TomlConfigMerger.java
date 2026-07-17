package dev.alvo.pieria.cli.modules.harness;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.config.toml.ConfigTreeStore;
import dev.alvo.pieria.tools.io.FileOps;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Loads, edits, and saves TOML config files ({@code config.toml}) for harnesses that use TOML.
 * Editing is structural and idempotent. Used by {@link CodexInstaller}. The tree model is the same
 * {@code JsonNode}/{@code ObjectNode} API as JSON; only the (de)serializer differs.
 */
public final class TomlConfigMerger {

  private final ConfigTreeStore store = new ConfigTreeStore(TomlMapper.builder().build());

  /**
   * Read the file as an object, or return a fresh empty object if absent/empty/non-object.
   */
  public ObjectNode load(Path file) throws IOException {
    return store.load(file);
  }

  /**
   * Serialize to TOML and write (creating parent dirs), or print the intended target on dry-run.
   */
  public void save(Path file, ObjectNode root, boolean dryRun, Logger log) throws IOException {
    if (dryRun) {
      log.info("  would write {}", file);
      return;
    }
    FileOps.writeFile(file, store.serialize(root));
    log.info("  wrote {}", file);
  }

  public ObjectNode childObject(ObjectNode parent, String field) {
    return store.childObject(parent, field);
  }

  public ArrayNode childArray(ObjectNode parent, String field) {
    return store.childArray(parent, field);
  }

  public ObjectNode newObject() {
    return store.newObject();
  }
}
