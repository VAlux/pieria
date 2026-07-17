package dev.alvo.pieria.cli.modules.harness;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.tools.io.FileOps;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts the embedded harness shell scripts (classpath resources under {@code harness/}) to the
 * on-disk harness directory. Idempotent: existing files are overwritten with the binary's
 * versioned copy. The shared scripts ({@code profile-name.sh}, {@code ingest.sh}, {@code recall.sh})
 * are always written; harness-specific scripts are added per installer.
 */
public final class HookAssetWriter {

  private static final List<String> SHARED_SCRIPTS =
    List.of("harness/profile-name.sh", "harness/ingest.sh", "harness/recall.sh", "harness/remember.sh");

  private final ClassLoader classLoader;

  public HookAssetWriter() {
    this(HookAssetWriter.class.getClassLoader());
  }

  public HookAssetWriter(ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  /**
   * Write the shared scripts plus {@code extraResources} under {@code harnessDir}, preserving the
   * path below the {@code harness/} prefix. Makes {@code .sh} files executable on POSIX systems.
   */
  public void extract(Path harnessDir, List<String> extraResources, boolean dryRun, Logger log)
    throws IOException {
    Set<String> resources = new LinkedHashSet<>(SHARED_SCRIPTS);
    resources.addAll(extraResources);
    for (String resource : resources) {
      String relative = resource.startsWith("harness/") ? resource.substring("harness/".length()) : resource;
      Path target = harnessDir.resolve(relative);
      if (dryRun) {
        log.info("  would extract {} -> {}", resource, target);
        continue;
      }
      try (InputStream in = classLoader.getResourceAsStream(resource)) {
        if (in == null) {
          throw new IOException("missing embedded harness resource: " + resource);
        }
        FileOps.ensureParentDirectory(target);
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      }
      FileOps.makeExecutable(target);
    }
    if (!dryRun) {
      log.info("  extracted hook scripts to {}", harnessDir);
    }
  }
}
