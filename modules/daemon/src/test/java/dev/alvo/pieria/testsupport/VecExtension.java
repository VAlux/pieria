package dev.alvo.pieria.testsupport;

import dev.alvo.pieria.tools.io.NativeResourceExtractor;
import dev.alvo.pieria.tools.os.OsFamily;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Locates the {@code sqlite-vec} loadable extension for tests, and enforces that it is actually
 * usable.
 *
 * <p><strong>Why this fails instead of skipping.</strong> Vector search is a core feature, but its
 * tests used to be guarded by {@code assumeTrue(store.isVectorSearchAvailable())}. A skipped test
 * reports as green, and Gradle prints no skip counts by default, so nothing anywhere — locally or in
 * CI — distinguished "vector search works" from "vector search was never exercised". That hid a real
 * defect: the suite loaded the extension by bare name only, which never resolves from the Gradle
 * working directory, so the vector assertions had been silently skipping on every platform.
 *
 * <p>The requirement is therefore mandatory by default: a missing or unloadable extension fails the
 * build. {@code PIERIA_ALLOW_MISSING_VEC_EXTENSION=1} (or {@code -Dpieria.test.allow-missing-vec-extension=true})
 * downgrades it to a skip, which exists only for offline work on a fresh clone — {@code
 * packaging/native/*} /{@code *.dylib,*.so,*.dll} are git-ignored, so a new checkout has no binary
 * until one is fetched. CI must never set it.
 */
public final class VecExtension {

  private static final String ALLOW_MISSING_ENV = "PIERIA_ALLOW_MISSING_VEC_EXTENSION";
  private static final String ALLOW_MISSING_PROPERTY = "pieria.test.allow-missing-vec-extension";

  private static boolean extractionAttempted;
  private static Path extracted;

  private VecExtension() {
  }

  /**
   * Resolve the extension exactly the way the daemon does at runtime: an explicit override first,
   * then the embedded {@code native/<os>-<arch>/vec0.*} classpath resource extracted to a temp file
   * — the same route as {@code VecExtensionResolver.extractEmbedded}.
   *
   * <p>Reading the resource rather than {@code packaging/native/} directly matters on macOS: a
   * {@code vec0.dylib} fetched with a browser carries {@code com.apple.quarantine}, and {@code
   * dlopen} then rejects it with "code signature not valid for use in process". Gradle's resource
   * copy drops the attribute, so the embedded copy — the one that actually ships — loads cleanly.
   *
   * <p>Deliberately does <em>not</em> consult {@code PIERIA_TEST_NATIVE_DIR}: CI points that at the
   * freshly built Tree-sitter output directory, which holds no {@code vec0}.
   */
  public static synchronized Optional<Path> locate() {
    Optional<Path> explicit = NativeResourceExtractor.existingFile(System.getenv("PIERIA_VEC_EXTENSION"))
      .or(() -> NativeResourceExtractor.existingFile(System.getProperty("pieria.vec.extension-path")));
    if (explicit.isPresent()) {
      return explicit;
    }

    if (!extractionAttempted) {
      extracted = extractFromClasspath().or(VecExtension::fromPackagingDirectory).orElse(null);
      extractionAttempted = true;
    }

    return Optional.ofNullable(extracted);
  }

  /** The embedded resource, extracted once per JVM to a temp file that outlives the calling test. */
  private static Optional<Path> extractFromClasspath() {
    String resource = "native/" + platformKey() + "/" + fileName();
    try (InputStream in = VecExtension.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        return Optional.empty();
      }
      Path target = Files.createTempDirectory("pieria-vec-test-").resolve(fileName());
      target.toFile().deleteOnExit();
      return Optional.of(NativeResourceExtractor.extract(in, target));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** Last resort for a build whose resources were not staged: the raw per-platform packaging dir. */
  private static Optional<Path> fromPackagingDirectory() {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("packaging/native"))) {
      root = root.getParent();
    }
    if (root == null) {
      return Optional.empty();
    }
    Path candidate = root.resolve("packaging/native").resolve(platformKey()).resolve(fileName());
    return Files.isRegularFile(candidate) ? Optional.of(candidate.toAbsolutePath().normalize()) : Optional.empty();
  }

  private static String platformKey() {
    return NativeResourceExtractor.platformKey(OsFamily.osName(), OsFamily.osArch());
  }

  private static String fileName() {
    return "vec0." + NativeResourceExtractor.librarySuffix(OsFamily.osName());
  }

  /**
   * Assert that the extension loaded. Fails the test when it did not, unless the opt-out is set, in
   * which case the test is aborted (skipped) with the same explanation.
   */
  public static void requireLoaded(boolean loaded) {
    if (loaded) {
      return;
    }
    String reason = locate()
      .map(path -> "the sqlite-vec extension at " + path + " failed to load")
      .orElseGet(() -> "no sqlite-vec extension was found for this platform ("
        + NativeResourceExtractor.platformKey(OsFamily.osName(), OsFamily.osArch())
        + "); see packaging/native/README.md to fetch it");
    if (allowMissing()) {
      abort("Skipping: " + reason + " (" + ALLOW_MISSING_ENV + " is set).");
    }
    fail("Vector search is unavailable because " + reason + ". Vector search is a required "
      + "capability, so this is a failure rather than a skip. For offline work on a fresh clone, set "
      + ALLOW_MISSING_ENV + "=1 to skip these tests instead; CI must not set it.");
  }

  /** True when the caller has explicitly opted out of the requirement. */
  public static boolean allowMissing() {
    String env = System.getenv(ALLOW_MISSING_ENV);
    if (env != null && !env.isBlank()) {
      return !"0".equals(env.trim()) && !"false".equalsIgnoreCase(env.trim());
    }
    return Boolean.getBoolean(ALLOW_MISSING_PROPERTY);
  }

}
