package dev.alvo.pieria.cli.modules.update;

import java.nio.file.Path;

/**
 * Windows placeholder. {@link #exeName(String)} appends {@code .exe}; the swap operations throw
 * until implemented. {@code pieria update} refuses to run via {@link #supported()} and points the
 * user at {@code install.ps1}.
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
  public boolean supported() {
    return false;
  }

  @Override
  public String exeName(String base) {
    return base + ".exe";
  }

  @Override
  public void harden(Path binary) {
    throw new UnsupportedOperationException("pieria update does not support Windows yet");
  }

  @Override
  public void extractTarGz(Path archive, Path destDir) {
    throw new UnsupportedOperationException("pieria update does not support Windows yet");
  }
}
