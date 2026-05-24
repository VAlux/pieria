package dev.alvo.pieria.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Locates the {@code sqlite-vec} loadable extension for the daemon (SPEC 14 packaging).
 *
 * <p>The platform {@code vec0} extension is embedded as a classpath resource ({@code native/vec0.*})
 * in both the native image and the boot jar, so the daemon ships as a single self-contained
 * artifact with no sidecar file. At startup the host-platform extension is extracted to the
 * app-data runtime directory and loaded by absolute path (the xerial driver runs its own SQLite
 * engine, so the extension cannot be statically linked into the native image — see
 * {@link VecProperties}).
 *
 * <p>Resolution order, first match wins:
 * <ol>
 *   <li>{@code pieria.vec.extension-path} when set to an existing file;</li>
 *   <li>the {@code PIERIA_VEC_EXTENSION} environment variable when set to an existing file;</li>
 *   <li>a {@code vec0.{dylib,so,dll}} sitting next to the running binary/jar or a sibling
 *       {@code lib/} directory — an ops escape hatch to patch the extension without rebuilding;</li>
 *   <li>the embedded {@code native/vec0.*} resource, extracted to the runtime directory.</li>
 * </ol>
 *
 * <p>An empty result is not an error: {@code DataSourceConfig} then falls back to the OS extension
 * search path, and finally to disabling vector search. The file-search and extraction steps are
 * static methods over explicit inputs so they can be unit-tested without a Spring context.
 */
@Component
public class VecExtensionResolver {

  private static final Logger log = LoggerFactory.getLogger(VecExtensionResolver.class);

  private final VecProperties properties;
  private final AppDataPathResolver pathResolver;

  public VecExtensionResolver(VecProperties properties, AppDataPathResolver pathResolver) {
    this.properties = properties;
    this.pathResolver = pathResolver;
  }

  /** Resolve the extension path from config/env/install layout, then the embedded resource. */
  public Optional<Path> resolve() {
    String osName = osName();
    Optional<Path> fileBased = resolve(
      properties.extensionPath(),
      System.getenv("PIERIA_VEC_EXTENSION"),
      installCandidateDirectories(),
      osName);
    if (fileBased.isPresent()) {
      return fileBased;
    }
    return extractEmbedded(osName, osArch());
  }

  /**
   * Pure file-search resolution. {@code candidateDirs} are searched for the platform extension file
   * only when neither explicit input points at an existing file.
   */
  static Optional<Path> resolve(String configuredPath,
                                String envPath,
                                List<Path> candidateDirs,
                                String osName) {
    Optional<Path> explicit = existingFile(configuredPath).or(() -> existingFile(envPath));
    if (explicit.isPresent()) {
      return explicit;
    }
    String fileName = platformExtensionFileName(osName);
    for (Path dir : candidateDirs) {
      Path candidate = dir.resolve(fileName);
      if (Files.isRegularFile(candidate)) {
        return Optional.of(candidate.toAbsolutePath().normalize());
      }
    }
    return Optional.empty();
  }

  /**
   * Extract the embedded extension for this OS+arch to the runtime directory. Returns empty when no
   * matching resource is bundled (e.g. a build with no extension supplied for this platform).
   */
  private Optional<Path> extractEmbedded(String osName, String osArch) {
    String resourcePath = embeddedResourcePath(osName, osArch);
    Path target = pathResolver.resolve().runtimeDir().resolve(platformExtensionFileName(osName));
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        log.debug("No embedded sqlite-vec extension at {} for this platform.", resourcePath);
        return Optional.empty();
      }
      return Optional.of(extract(in, target));
    } catch (IOException e) {
      log.warn("Could not extract embedded sqlite-vec extension to {} ({}); "
        + "vector search may be disabled.", target, e.toString());
      return Optional.empty();
    }
  }

  /**
   * Copy {@code in} to {@code target}, creating parent directories. The write is skipped when the
   * target already exists with identical content (SHA-256 match), so re-launches are fast and an
   * upgrade to a new vec0 binary always replaces the stale file.
   */
  static Path extract(InputStream in, Path target) throws IOException {
    byte[] bytes = in.readAllBytes();
    Path parent = target.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    if (!Files.isRegularFile(target) || !sha256Matches(target, bytes)) {
      Files.write(target, bytes);
    }
    return target.toAbsolutePath().normalize();
  }

  private static boolean sha256Matches(Path file, byte[] expected) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] onDisk = md.digest(Files.readAllBytes(file));
      return Arrays.equals(onDisk, md.digest(expected));
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Classpath resource path for the embedded platform extension, arch-scoped because Apple Silicon
   * and Intel macs (and ARM vs x86 Linux) share a suffix but need different binaries — e.g.
   * {@code native/macos-aarch64/vec0.dylib}.
   */
  static String embeddedResourcePath(String osName, String osArch) {
    return "native/" + platformKey(osName, osArch) + "/" + platformExtensionFileName(osName);
  }

  /** Canonical {@code <os>-<arch>} key matching the embedded resource and packaging layout. */
  static String platformKey(String osName, String osArch) {
    return osToken(osName) + "-" + archToken(osArch);
  }

  private static String osToken(String osName) {
    String os = osName.toLowerCase(Locale.ROOT);
    if (os.contains("mac") || os.contains("darwin")) {
      return "macos";
    }
    if (os.contains("win")) {
      return "windows";
    }
    return "linux";
  }

  private static String archToken(String osArch) {
    String arch = osArch.toLowerCase(Locale.ROOT);
    if (arch.contains("aarch64") || arch.contains("arm64")) {
      return "aarch64";
    }
    // amd64 (JVM) and x86_64 both mean 64-bit x86; sqlite-vec ships no 32-bit desktop builds.
    return "x86_64";
  }

  /**
   * Loadable-extension filename per OS. sqlite-vec releases ship {@code vec0} with the platform's
   * native shared-library suffix.
   */
  static String platformExtensionFileName(String osName) {
    return "vec0." + switch (osToken(osName)) {
      case "macos" -> "dylib";
      case "windows" -> "dll";
      default -> "so";
    };
  }

  private static Optional<Path> existingFile(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    Path path = Path.of(value);
    return Files.isRegularFile(path) ? Optional.of(path.toAbsolutePath().normalize()) : Optional.empty();
  }

  /**
   * Directories to search for a sidecar extension, derived from the location of the running code.
   * For a native image this is the executable's directory; for a boot jar it is the jar directory.
   * We also check a sibling {@code lib} because the JVM distribution places jars under {@code lib}.
   */
  private static List<Path> installCandidateDirectories() {
    List<Path> dirs = new ArrayList<>();
    Path codeLocation = codeSourceDirectory();
    if (codeLocation != null) {
      dirs.add(codeLocation);
      Path parent = codeLocation.getParent();
      if (parent != null) {
        dirs.add(parent);
        dirs.add(parent.resolve("lib"));
      }
    }
    return dirs;
  }

  private static Path codeSourceDirectory() {
    try {
      CodeSource source = VecExtensionResolver.class.getProtectionDomain().getCodeSource();
      if (source == null || source.getLocation() == null) {
        return null;
      }
      Path location = Path.of(source.getLocation().toURI());
      return Files.isDirectory(location) ? location : location.getParent();
    } catch (Exception e) {
      return null;
    }
  }

  private static String osName() {
    return System.getProperty("os.name", "");
  }

  private static String osArch() {
    return System.getProperty("os.arch", "");
  }
}
