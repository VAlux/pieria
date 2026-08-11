package dev.alvo.pieria.cli.modules.update;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseSourceTests {

  private static final byte[] TARBALL = "fake-tarball-bytes".getBytes();
  private final TestPlatform platform = new TestPlatform();

  private static UnaryOperator<String> env(Map<String, String> map) {
    return map::get;
  }

  private static String sha256(byte[] bytes) throws Exception {
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder sb = new StringBuilder();
    for (byte b : hash) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  /**
   * Fetcher that serves the tarball for any URL ending in {@code .tar.gz} and the given checksums
   * body for {@code checksums.txt}; returns 404 for checksums when {@code checksumsBody} is null.
   */
  private ReleaseSource.Fetcher fetcher(String checksumsBody) {
    return (url, dest) -> {
      try {
        if (url.endsWith(".tar.gz")) {
          Files.write(dest, TARBALL);
          return 200;
        }
        if (url.endsWith("checksums.txt")) {
          if (checksumsBody == null) {
            return 404;
          }
          Files.writeString(dest, checksumsBody);
          return 200;
        }
        return 404;
      } catch (Exception e) {
        return -1;
      }
    };
  }

  @Test
  void describesLatestAndTaggedUrls() {
    ReleaseSource latest = new ReleaseSource(platform, "latest", env(Map.of()), fetcher(null));
    assertThat(latest.describe())
      .contains("releases/latest/download")
      .contains("pieria-test-arch.tar.gz");

    ReleaseSource tagged = new ReleaseSource(platform, "v1.2.3", env(Map.of()), fetcher(null));
    assertThat(tagged.describe()).contains("releases/download/v1.2.3");
  }

  @Test
  void honorsRepoAndBaseUrlOverrides() {
    ReleaseSource repo = new ReleaseSource(platform, "latest", env(Map.of("PIERIA_REPO", "acme/pieria")), fetcher(null));
    assertThat(repo.describe()).contains("github.com/acme/pieria");

    ReleaseSource base = new ReleaseSource(platform, "latest",
      env(Map.of("PIERIA_BASE_URL", "https://mirror.example/dl/")), fetcher(null));
    assertThat(base.describe()).contains("https://mirror.example/dl/pieria-test-arch.tar.gz");
  }

  @Test
  void resolvesAndExtractsWhenChecksumMatches() throws Exception {
    String checksums = sha256(TARBALL) + "  pieria-test-arch.tar.gz\n";
    StagedDist dist = new ReleaseSource(platform, "latest", env(Map.of()), fetcher(checksums)).resolve();
    assertThat(platform.extracted).hasSize(1);
  }

  @Test
  void resolvesWhenNoChecksumsPublished() {
    StagedDist dist = new ReleaseSource(platform, "latest", env(Map.of()), fetcher(null)).resolve();
    assertThat(dist).isNotNull();
    assertThat(platform.extracted).hasSize(1);
  }

  @Test
  void failsOnChecksumMismatch() {
    String checksums = "deadbeef  pieria-test-arch.tar.gz\n";
    assertThatThrownBy(() -> new ReleaseSource(platform, "latest", env(Map.of()), fetcher(checksums)).resolve())
      .isInstanceOf(UpdateException.class)
      .hasMessageContaining("checksum mismatch");
  }

  @Test
  void failsWhenTarballMissing() {
    ReleaseSource.Fetcher notFound = (url, dest) -> 404;
    assertThatThrownBy(() -> new ReleaseSource(platform, "latest", env(Map.of()), notFound).resolve())
      .isInstanceOf(UpdateException.class)
      .hasMessageContaining("could not download");
  }

  /** Windows publishes a zip; the asset name and URL must follow the platform, not a hardcoded suffix. */
  @Test
  void usesThePlatformsArchiveExtension() {
    Platform windows = new WindowsPlatform("x86_64");
    assertThat(new ReleaseSource(windows, "latest", env(Map.of()), fetcher(null)).describe())
      .contains("pieria-windows-x86_64.zip");
  }

  @Test
  void refusesToDownloadForAnUnpublishedPlatform() {
    Platform unpublished = new LinuxPlatform("aarch64");
    assertThatThrownBy(() ->
      new ReleaseSource(unpublished, "latest", env(Map.of()), fetcher(null)).resolve())
      .isInstanceOf(UpdateException.class)
      .hasMessageContaining("no release build for 'linux-aarch64'")
      .hasMessageContaining("--from-build");
  }

  /** A self-built archive served through PIERIA_BASE_URL is the documented escape hatch. */
  @Test
  void baseUrlOverrideBypassesTheUnpublishedPlatformCheck() {
    Platform unpublished = new LinuxPlatform("aarch64", command -> new CommandRunner.Result(0, ""));
    assertThatThrownBy(() -> new ReleaseSource(unpublished, "latest",
      env(Map.of("PIERIA_BASE_URL", "https://mirror.example/dl")), (url, dest) -> 404).resolve())
      .isInstanceOf(UpdateException.class)
      .hasMessageContaining("could not download");
  }
}
