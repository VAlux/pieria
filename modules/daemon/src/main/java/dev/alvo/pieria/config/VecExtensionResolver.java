package dev.alvo.pieria.config;

import dev.alvo.pieria.tools.os.OsFamily;
import dev.alvo.pieria.tools.io.NativeResourceExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Locates the {@code sqlite-vec} loadable extension for the daemon.
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
    String osName = OsFamily.osName();
    Optional<Path> fileBased = resolve(
      properties.extensionPath(),
      System.getenv("PIERIA_VEC_EXTENSION"),
      NativeResourceExtractor.installCandidateDirectories(VecExtensionResolver.class),
      osName);
    if (fileBased.isPresent()) {
      return fileBased;
    }
    return extractEmbedded(osName, OsFamily.osArch());
  }

  /**
   * Pure file-search resolution. {@code candidateDirs} are searched for the platform extension file
   * only when neither explicit input points at an existing file.
   */
  static Optional<Path> resolve(String configuredPath,
                                String envPath,
                                List<Path> candidateDirs,
                                String osName) {
    Optional<Path> explicit = NativeResourceExtractor.existingFile(configuredPath)
      .or(() -> NativeResourceExtractor.existingFile(envPath));
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

  static Path extract(InputStream in, Path target) throws IOException {
    return NativeResourceExtractor.extract(in, target);
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
    return NativeResourceExtractor.platformKey(osName, osArch);
  }

  /**
   * Loadable-extension filename per OS. sqlite-vec releases ship {@code vec0} with the platform's
   * native shared-library suffix.
   */
  static String platformExtensionFileName(String osName) {
    return "vec0." + NativeResourceExtractor.librarySuffix(osName);
  }

}
