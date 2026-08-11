package dev.alvo.pieria.cli.modules.update;

import java.nio.file.Path;

/**
 * Linux implementation. Nothing to harden — there is no Gatekeeper and no quarantine attribute, so a
 * downloaded binary is launchable as soon as its exec bit is set (which {@link BinarySwapper} does).
 * Extraction goes through {@link TarArchive}, shared with {@link MacOsPlatform}.
 */
public final class LinuxPlatform implements Platform {

  private final String arch;
  private final CommandRunner runner;

  public LinuxPlatform(String arch) {
    this(arch, CommandRunner.real());
  }

  LinuxPlatform(String arch, CommandRunner runner) {
    this.arch = arch;
    this.runner = runner;
  }

  @Override
  public String slug() {
    return "linux-" + arch;
  }

  @Override
  public void harden(Path binary) {
    // No-op: Linux has no equivalent of macOS quarantine/codesigning.
  }

  @Override
  public void extractDistributionArchive(Path archive, Path destDir) {
    TarArchive.extract(runner, archive, destDir);
  }
}
