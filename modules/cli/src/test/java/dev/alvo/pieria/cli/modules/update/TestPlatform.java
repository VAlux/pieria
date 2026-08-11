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
  private boolean locksRunningBinaries;

  TestPlatform() {
    this("test-arch");
  }

  TestPlatform(String slug) {
    this.slug = slug;
  }

  /** Behave like Windows: the OS refuses to overwrite a running executable. */
  TestPlatform lockingRunningBinaries() {
    this.locksRunningBinaries = true;
    return this;
  }

  @Override
  public String slug() {
    return slug;
  }

  /**
   * The fake slug is deliberately not in {@code PUBLISHED_PLATFORMS}; report it as published anyway
   * so tests exercise the download path rather than the preflight. The preflight has its own test.
   */
  @Override
  public boolean hasPublishedRelease() {
    return true;
  }

  @Override
  public boolean locksRunningBinaries() {
    return locksRunningBinaries;
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
