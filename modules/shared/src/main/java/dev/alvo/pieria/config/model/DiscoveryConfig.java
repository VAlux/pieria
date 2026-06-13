package dev.alvo.pieria.config.model;

import java.util.Set;

/**
 * The {@code [discovery]} section of a Pieria config file: which files the CLI's source-code
 * discovery sends to the daemon's code index. Consumed client-side only — never pushed to the
 * daemon.
 *
 * <p>Every component is nullable on the wire; a {@code null} (absent key) inherits the code-baked
 * default, so a config file only needs to state what it changes. The compact constructor
 * normalizes, so accessors never return {@code null}. An explicitly empty list is honored as-is
 * (e.g. {@code build-markers = []} disables build-marker matching).
 */
public record DiscoveryConfig(
  Set<String> sourceExtensions,
  Set<String> buildMarkers,
  Set<String> skipDirs,
  Long maxFileBytes) {

  public static final Set<String> DEFAULT_SOURCE_EXTENSIONS = Set.of(
    "java", "kt", "kts", "scala", "sc",
    "ts", "tsx", "js", "jsx", "mjs", "cjs",
    "py", "go", "rs", "rb", "php", "cs",
    "c", "h", "cpp", "cc", "hpp", "swift");

  public static final Set<String> DEFAULT_BUILD_MARKERS = Set.of(
    "build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle",
    "pom.xml", "package.json", "go.mod", "Cargo.toml");

  public static final Set<String> DEFAULT_SKIP_DIRS =
    Set.of(".git", "node_modules", "build", ".gradle", ".idea", "target", "dist");

  /**
   * Files larger than this are skipped (generated bundles, vendored blobs, data files).
   */
  public static final long DEFAULT_MAX_FILE_BYTES = 1_048_576; // 1 MiB

  public DiscoveryConfig {
    sourceExtensions = sourceExtensions == null ? DEFAULT_SOURCE_EXTENSIONS : Set.copyOf(sourceExtensions);
    buildMarkers = buildMarkers == null ? DEFAULT_BUILD_MARKERS : Set.copyOf(buildMarkers);
    skipDirs = skipDirs == null ? DEFAULT_SKIP_DIRS : Set.copyOf(skipDirs);
    maxFileBytes = maxFileBytes == null ? DEFAULT_MAX_FILE_BYTES : maxFileBytes;
  }

  /** All-defaults instance, equal to an absent {@code [discovery]} section. */
  public static DiscoveryConfig defaults() {
    return new DiscoveryConfig(null, null, null, null);
  }
}
