package dev.alvo.pieria.cli.modules.update;

import dev.alvo.pieria.tools.io.ZipArchive;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Windows implementation. Two things differ from the Unix platforms: the release asset is a zip
 * rather than a tarball, and the OS holds an exclusive lock on a running executable's image — see
 * {@link #locksRunningBinaries()} and {@link BinarySwapper} for how the swap works around that.
 * Nothing to harden: there is no Gatekeeper and no quarantine attribute.
 */
public final class WindowsPlatform implements Platform {

  private final String arch;

  public WindowsPlatform(String arch) {
    this.arch = arch;
  }

  @Override
  public String slug() {
    return "windows-" + arch;
  }

  @Override
  public String exeName(String base) {
    return base + ".exe";
  }

  @Override
  public String archiveExtension() {
    return "zip";
  }

  @Override
  public boolean locksRunningBinaries() {
    return true;
  }

  @Override
  public void harden(Path binary) {
    // No-op: Windows has no equivalent of macOS quarantine/codesigning.
  }

  @Override
  public void extractDistributionArchive(Path archive, Path destDir) {
    try {
      ZipArchive.extract(archive, destDir);
    } catch (IOException e) {
      throw new UpdateException("failed to extract " + archive.getFileName() + ": " + e.getMessage(), e);
    }
  }
}
