package dev.alvo.pieria.config;

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
 * Locates the Tree-sitter native libraries (core {@code libtree-sitter} and per-language grammars)
 * for the code-index parser, mirroring {@link VecExtensionResolver}.
 *
 * <p>Each library is bundled as a classpath resource ({@code native/<os>-<arch>/<lib>.<suffix>}) and
 * extracted to the app-data runtime directory, then loaded by absolute path (FFM {@code dlopen}), so
 * the daemon ships self-contained with no sidecar. Resolution order per library, first match wins:
 * configured path → environment variable → a file next to the running binary/jar (or sibling
 * {@code lib/}) → the embedded resource. An empty result is not an error — {@link TreeSitterEngine}
 * then degrades that language (or all of Tree-sitter) gracefully.
 */
@Component
public class TreeSitterLibraryResolver {

  private static final Logger log = LoggerFactory.getLogger(TreeSitterLibraryResolver.class);

  private final TreeSitterProperties properties;
  private final AppDataPathResolver pathResolver;

  public TreeSitterLibraryResolver(TreeSitterProperties properties, AppDataPathResolver pathResolver) {
    this.properties = properties;
    this.pathResolver = pathResolver;
  }

  /** Resolve the core {@code libtree-sitter} library. */
  public Optional<Path> resolveCore() {
    return resolve("libtree-sitter", properties.corePath(), System.getenv("PIERIA_TREESITTER_CORE"));
  }

  /** Resolve the grammar library for {@code language} (currently only {@code java}). */
  public Optional<Path> resolveGrammar(String language) {
    if ("java".equals(language)) {
      return resolve("tree-sitter-java", properties.javaGrammarPath(),
        System.getenv("PIERIA_TREESITTER_JAVA_GRAMMAR"));
    }
    return Optional.empty();
  }

  private Optional<Path> resolve(String baseName, String configuredPath, String envPath) {
    Optional<Path> explicit = NativeResourceExtractor.existingFile(configuredPath)
      .or(() -> NativeResourceExtractor.existingFile(envPath));
    if (explicit.isPresent()) {
      return explicit;
    }
    String osName = osName();
    String fileName = baseName + "." + NativeResourceExtractor.librarySuffix(osName);
    List<Path> candidateDirs = NativeResourceExtractor.installCandidateDirectories(TreeSitterLibraryResolver.class);
    for (Path dir : candidateDirs) {
      Path candidate = dir.resolve(fileName);
      if (Files.isRegularFile(candidate)) {
        return Optional.of(candidate.toAbsolutePath().normalize());
      }
    }
    return extractEmbedded(baseName, osName, osArch());
  }

  private Optional<Path> extractEmbedded(String baseName, String osName, String osArch) {
    String fileName = baseName + "." + NativeResourceExtractor.librarySuffix(osName);
    String resourcePath = "native/" + NativeResourceExtractor.platformKey(osName, osArch) + "/" + fileName;
    Path target = pathResolver.resolve().runtimeDir().resolve(fileName);
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        log.debug("No embedded Tree-sitter library at {} for this platform.", resourcePath);
        return Optional.empty();
      }
      return Optional.of(NativeResourceExtractor.extract(in, target));
    } catch (IOException e) {
      log.warn("Could not extract embedded Tree-sitter library to {} ({}); that language is skipped.",
        target, e.toString());
      return Optional.empty();
    }
  }

  private static String osName() {
    return System.getProperty("os.name", "");
  }

  private static String osArch() {
    return System.getProperty("os.arch", "");
  }
}
