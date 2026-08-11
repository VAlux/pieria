package dev.alvo.pieria.cli.modules.update;

import dev.alvo.pieria.cli.log.Logger;
import java.nio.file.Path;
import java.util.List;

/**
 * macOS implementation. Hardening clears the quarantine xattr and ad-hoc codesigns the binary so a
 * freshly-written (downloaded or copied) executable is not blocked by Gatekeeper — the manual
 * redeploy's biggest footgun. Extraction goes through {@link TarArchive}, shared with
 * {@link LinuxPlatform}.
 */
public final class MacOsPlatform implements Platform {

  private static final Logger log = new Logger();

  private final String arch;
  private final CommandRunner runner;

  public MacOsPlatform(String arch) {
    this(arch, CommandRunner.real());
  }

  MacOsPlatform(String arch, CommandRunner runner) {
    this.arch = arch;
    this.runner = runner;
  }

  @Override
  public String slug() {
    return "macos-" + arch;
  }

  @Override
  public void harden(Path binary) {
    // Quarantine may be absent (locally-built binaries) — `xattr -d` is tolerant, so ignore its exit.
    runner.run(List.of("xattr", "-dr", "com.apple.quarantine", binary.toString()));
    CommandRunner.Result sign = runner.run(List.of("codesign", "--force", "--sign", "-", binary.toString()));
    if (!sign.ok()) {
      log.error("warning: codesign of {} failed ({}); it may be blocked by Gatekeeper.",
        binary.getFileName(), sign.output() == null || sign.output().isBlank() ? "no output" : sign.output().strip());
    }
  }

  @Override
  public void extractDistributionArchive(Path archive, Path destDir) {
    TarArchive.extract(runner, archive, destDir);
  }
}
