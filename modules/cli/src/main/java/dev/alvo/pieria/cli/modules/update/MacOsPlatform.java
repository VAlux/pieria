package dev.alvo.pieria.cli.modules.update;

import java.nio.file.Path;
import java.util.List;

/**
 * macOS implementation. Hardening clears the quarantine xattr and ad-hoc codesigns the binary so a
 * freshly-written (downloaded or copied) executable is not blocked by Gatekeeper — the manual
 * redeploy's biggest footgun. Extraction shells out to {@code tar} (present on macOS); kept behind
 * the {@link Platform} seam so a pure-Java reader can replace it later without touching callers.
 */
public final class MacOsPlatform implements Platform {

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
      System.err.printf("warning: codesign of %s failed (%s); it may be blocked by Gatekeeper.%n",
        binary.getFileName(), sign.output() == null || sign.output().isBlank() ? "no output" : sign.output().strip());
    }
  }

  @Override
  public void extractTarGz(Path archive, Path destDir) {
    CommandRunner.Result result = runner.run(List.of("tar", "-xzf", archive.toString(), "-C", destDir.toString()));
    if (!result.ok()) {
      throw new UpdateException("failed to extract " + archive.getFileName() + ": "
        + (result.output() == null ? "exit " + result.exitCode() : result.output().strip()));
    }
  }
}
