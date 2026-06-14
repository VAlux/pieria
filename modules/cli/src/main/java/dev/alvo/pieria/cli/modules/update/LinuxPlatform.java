package dev.alvo.pieria.cli.modules.update;

import java.nio.file.Path;

/**
 * Linux placeholder. Slug/exe-name are correct; the swap operations throw until implemented.
 * {@code pieria update} refuses to run via {@link #supported()} and points the user at the
 * installer. Finishing Linux support is "fill in {@link #harden(Path)}/{@link #extractDistributionArchive} and
 * flip {@link #supported()}".
 */
public final class LinuxPlatform implements Platform {

  private final String arch;

  public LinuxPlatform(String arch) {
    this.arch = arch;
  }

  @Override
  public String slug() {
    return "linux-" + arch;
  }

  @Override
  public boolean supported() {
    return false;
  }

  @Override
  public void harden(Path binary) {
    throw new UnsupportedOperationException("pieria update does not support Linux yet");
  }

  @Override
  public void extractDistributionArchive(Path archive, Path destDir) {
    throw new UnsupportedOperationException("pieria update does not support Linux yet");
  }
}
