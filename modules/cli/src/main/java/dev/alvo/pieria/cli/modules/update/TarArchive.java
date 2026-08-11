package dev.alvo.pieria.cli.modules.update;

import java.nio.file.Path;
import java.util.List;

/**
 * Extracts a {@code .tar.gz} release archive by shelling out to {@code tar}, shared by the two Unix
 * {@link Platform} implementations.
 *
 * <p>Deliberately not a pure-Java reader (unlike {@link dev.alvo.pieria.tools.io.ZipArchive}):
 * {@code tar} is present on every macOS and Linux install we target, and hand-rolling ustar/pax
 * header parsing would add a real correctness surface for no benefit.
 */
final class TarArchive {

  private TarArchive() {
  }

  static void extract(CommandRunner runner, Path archive, Path destDir) {
    CommandRunner.Result result =
      runner.run(List.of("tar", "-xzf", archive.toString(), "-C", destDir.toString()));
    if (!result.ok()) {
      throw new UpdateException("failed to extract " + archive.getFileName() + ": "
        + (result.output() == null ? "exit " + result.exitCode() : result.output().strip()));
    }
  }
}
