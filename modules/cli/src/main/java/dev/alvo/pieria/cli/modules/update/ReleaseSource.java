package dev.alvo.pieria.cli.modules.update;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.tools.Hash;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Downloads a published release for the host platform, verifies its SHA-256, and extracts it.
 * Reimplements the download path of {@code packaging/install.sh} in Java so the same logic works
 * cross-platform without a shell. Honors {@code PIERIA_REPO} and {@code PIERIA_BASE_URL} like the
 * installer.
 */
public final class ReleaseSource implements BinarySource {

  private static final Logger log = new Logger();

  static final String DEFAULT_REPO = "VAlux/pieria";

  private final Platform platform;
  private final String version;
  private final UnaryOperator<String> env;
  private final Fetcher fetcher;

  public ReleaseSource(Platform platform, String version) {
    this(platform, version, System::getenv, Fetcher.real());
  }

  ReleaseSource(Platform platform, String version, UnaryOperator<String> env, Fetcher fetcher) {
    this.platform = platform;
    this.version = (version == null || version.isBlank()) ? "latest" : version;
    this.env = env;
    this.fetcher = fetcher;
  }

  private static String sha256(Path file) {
    try {
      return Hash.sha256Hex(file);
    } catch (RuntimeException e) {
      throw new UpdateException("could not hash " + file + ": " + e.getMessage(), e);
    }
  }

  @Override
  public String describe() {
    return "release " + version + " (" + releaseBase() + "/" + assetName() + ")";
  }

  /** Published release asset for the host platform, e.g. {@code pieria-windows-x86_64.zip}. */
  private String assetName() {
    return "pieria-" + platform.slug() + "." + platform.archiveExtension();
  }

  /**
   * Stop before downloading when no release is published for the host platform, so the user gets the
   * source-build guidance instead of a bare 404. Mirrors {@code assert_supported_platform} in
   * {@code packaging/install.sh}. A {@code PIERIA_BASE_URL} override opts out — that is how someone
   * serves a self-built archive for an unpublished architecture.
   */
  private void assertPublished() {
    String override = env.apply("PIERIA_BASE_URL");
    if (platform.hasPublishedRelease() || (override != null && !override.isBlank())) {
      return;
    }
    throw new UpdateException("no release build for '" + platform.slug() + "'."
      + "\nPublished platforms: " + String.join(", ", PlatformSupport.PUBLISHED_PLATFORMS) + "."
      + "\nBuild from source and install that instead:"
      + "\n  ./gradlew :daemon:nativeDist && pieria update --from-build");
  }

  @Override
  public StagedDist resolve() {
    assertPublished();
    String asset = assetName();
    String base = releaseBase();
    Path work;
    try {
      work = Files.createTempDirectory("pieria-update");
    } catch (IOException e) {
      throw new UpdateException("could not create temp dir: " + e.getMessage(), e);
    }

    Path archive = work.resolve(asset);
    int status = fetcher.fetch(base + "/" + asset, archive);
    if (status < 200 || status >= 300) {
      throw new UpdateException("could not download " + asset + " from " + base
        + " (HTTP " + status + "). Check the version tag and your network.");
    }

    verifyChecksum(base, asset, archive, work);

    Path extracted = work.resolve("extracted");
    try {
      Files.createDirectories(extracted);
    } catch (IOException e) {
      throw new UpdateException("could not stage extraction dir: " + e.getMessage(), e);
    }
    platform.extractDistributionArchive(archive, extracted);
    return new StagedDist(extracted);
  }

  /**
   * Optional integrity check: verify only when {@code checksums.txt} is published and has an entry
   * for our asset, matching {@code install.sh}'s tolerant behavior.
   */
  private void verifyChecksum(String base, String asset, Path archive, Path work) {
    Path checksums = work.resolve("checksums.txt");
    int status = fetcher.fetch(base + "/checksums.txt", checksums);
    if (status < 200 || status >= 300 || !Files.isRegularFile(checksums)) {
      log.error("warning: no checksums.txt published; skipping integrity verification.");
      return;
    }
    String expected = lookup(checksums, asset);
    if (expected == null) {
      log.error("warning: no checksum entry for {}; skipping verification.", asset);
      return;
    }
    String actual = sha256(archive);
    if (!actual.equalsIgnoreCase(expected)) {
      throw new UpdateException("checksum mismatch for " + asset
        + " (expected " + expected + ", got " + actual + ").");
    }
  }

  /**
   * Find the hash for {@code asset} in a {@code sha256sum}-style file; matches both {@code "<hash>  name"}
   * and the BSD {@code "<hash> *name"} form.
   */
  private String lookup(Path checksums, String asset) {
    try {
      for (String line : Files.readAllLines(checksums)) {
        String[] parts = line.trim().split("\\s+", 2);
        if (parts.length == 2) {
          String name = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
          if (name.equals(asset)) {
            return parts[0];
          }
        }
      }
    } catch (IOException e) {
      return null;
    }
    return null;
  }

  private String releaseBase() {
    String override = env.apply("PIERIA_BASE_URL");
    if (override != null && !override.isBlank()) {
      return override.endsWith("/") ? override.substring(0, override.length() - 1) : override;
    }
    String repo = env.apply("PIERIA_REPO");
    repo = (repo == null || repo.isBlank()) ? DEFAULT_REPO : repo;
    return "latest".equals(version.toLowerCase(Locale.ROOT))
      ? "https://github.com/" + repo + "/releases/latest/download"
      : "https://github.com/" + repo + "/releases/download/" + version;
  }

  /**
   * Downloads a URL to a file, returning the HTTP status (or {@code -1} on transport failure). The
   * destination is only written on a 2xx response. Injectable so tests serve local fixtures.
   */
  @FunctionalInterface
  interface Fetcher {

    int fetch(String url, Path dest);

    static Fetcher real() {
      HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
      return (url, dest) -> {
        try {
          HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build();
          HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
          if (response.statusCode() >= 200 && response.statusCode() < 300) {
            Files.write(dest, response.body());
          }
          return response.statusCode();
        } catch (IOException | InterruptedException e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
          return -1;
        }
      };
    }
  }
}
