package dev.alvo.pieria.cli.modules.harness;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.tools.io.FileOps;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes user-triggered slash-command templates (classpath resources under {@code harness/}) to a
 * harness's command directory, substituting placeholders (notably {@code <PIERIA_BIN>}) with their
 * resolved absolute values, so a template can invoke the installed {@code pieria} binary by absolute
 * path. Idempotent: re-installing overwrites; uninstall deletes only the file it wrote.
 */
public final class CommandAssetWriter {

  private final ClassLoader classLoader;

  public CommandAssetWriter() {
    this(CommandAssetWriter.class.getClassLoader());
  }

  public CommandAssetWriter(ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  /**
   * Read {@code resource}, apply {@code substitutions}, and write it to {@code target}.
   */
  public void write(String resource, Path target, Map<String, String> substitutions,
                    boolean dryRun, Logger log) throws IOException {
    if (dryRun) {
      log.info("  would write command {} -> {}", resource, target);
      return;
    }
    String content;
    try (InputStream in = classLoader.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("missing embedded command resource: " + resource);
      }
      content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    for (Map.Entry<String, String> entry : substitutions.entrySet()) {
      content = content.replace(entry.getKey(), entry.getValue());
    }
    FileOps.writeFile(target, content);
    log.info("  wrote command {}", target);
  }

  /**
   * Delete a previously written command file, if present.
   */
  public void delete(Path target, boolean dryRun, Logger log) throws IOException {
    if (dryRun) {
      log.info("  would remove command {}", target);
      return;
    }
    if (Files.deleteIfExists(target)) {
      log.info("  removed command {}", target);
    }
  }
}
