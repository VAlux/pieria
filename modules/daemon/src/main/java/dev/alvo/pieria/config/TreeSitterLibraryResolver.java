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
 * Locates the Tree-sitter native libraries (core {@code libtree-sitter} and per-language grammars)
 * for the code-index parser, mirroring {@link VecExtensionResolver}.
 *
 * <p>Each library is bundled as a classpath resource ({@code native/<os>-<arch>/<lib>.<suffix>}) and
 * extracted to the app-data runtime directory, then loaded by absolute path (FFM {@code dlopen}), so
 * the daemon ships self-contained with no sidecar. Resolution order per library, first match wins:
 * configured path → environment variable → a file next to the running binary/jar (or sibling
 * {@code lib/}) → the embedded resource for the core runtime. Grammar locations are deliberately
 * not configurable: every language grammar is extracted from the daemon's embedded resources. An
 * empty result is not an error — {@link TreeSitterEngine} then degrades that language (or all of
 * Tree-sitter) gracefully.
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

  /**
   * Resolve the core {@code libtree-sitter} library.
   */
  public Optional<Path> resolveCore() {
    return resolve("libtree-sitter", properties.corePath(), System.getenv("PIERIA_TREESITTER_CORE"));
  }

  /**
   * Resolve an embedded grammar library. TypeScript and TSX share one library.
   */
  public Optional<Path> resolveGrammar(String language) {
    String baseName = switch (language) {
      case "java", "javascript", "typescript", "scss", "kotlin", "scala", "python", "go",
           "rust", "ruby", "php", "c-sharp", "c", "cpp", "swift" -> "tree-sitter-" + language;
      case "tsx" -> "tree-sitter-typescript";
      default -> null;
    };
    return baseName == null ? Optional.empty() : extractEmbedded(baseName, OsFamily.osName(), OsFamily.osArch());
  }

  private Optional<Path> resolve(String baseName, String configuredPath, String envPath) {
    String osName = OsFamily.osName();
    String fileName = baseName + "." + NativeResourceExtractor.librarySuffix(osName);
    List<Path> candidateDirs = NativeResourceExtractor.installCandidateDirectories(TreeSitterLibraryResolver.class);
    return NativeResourceExtractor.existingFile(configuredPath)
      .or(() -> NativeResourceExtractor.existingFile(envPath))
      .or(() -> candidateDirs.stream()
        .map(dir -> dir.resolve(fileName))
        .filter(Files::isRegularFile)
        .findFirst()
        .map(candidate -> candidate.toAbsolutePath().normalize()))
      .or(() -> extractEmbedded(baseName, osName, OsFamily.osArch()));
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

}
