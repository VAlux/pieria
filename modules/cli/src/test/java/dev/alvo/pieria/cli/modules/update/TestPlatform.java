package dev.alvo.pieria.cli.modules.update;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A controllable {@link Platform} for tests: records hardened binaries and extraction calls, and
 * uses identity exe names so cross-platform test machines behave the same.
 */
final class TestPlatform implements Platform {

  final List<Path> hardened = new ArrayList<>();
  final List<Path> extracted = new ArrayList<>();
  private final String slug;

  TestPlatform() {
    this("test-arch");
  }

  TestPlatform(String slug) {
    this.slug = slug;
  }

  @Override
  public String slug() {
    return slug;
  }

  @Override
  public void harden(Path binary) {
    hardened.add(binary);
  }

  @Override
  public void extractDistributionArchive(Path archive, Path destDir) {
    extracted.add(archive);
  }
}
