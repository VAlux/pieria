package dev.alvo.pieria.config.toml;

import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads a Pieria TOML config file into the Jackson tree model. Used by the CLI for both the
 * global {@code config.toml} (OS config dir) and a project's {@code .pieria/config.toml}; the
 * resulting trees are layered with {@link ConfigMerge} and bound via {@link ConfigCodec}.
 *
 * <p>Missing, empty, or non-object files load as an empty object — an absent layer simply
 * contributes nothing to the merge.
 */
public final class PieriaTomlLoader {

  private final ConfigTreeStore store = new ConfigTreeStore(TomlMapper.builder().build());

  /**
   * Read the file as an object tree, or return a fresh empty object when absent/empty/non-object.
   */
  public ObjectNode load(Path file) throws IOException {
    return store.load(file);
  }
}
