package dev.alvo.pieria.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
    Optional<Path> explicit = existingFile(configuredPath).or(() -> existingFile(envPath));
    if (explicit.isPresent()) {
      return explicit;
    }
    String osName = osName();
    String fileName = baseName + "." + suffix(osName);
    for (Path dir : installCandidateDirectories()) {
      Path candidate = dir.resolve(fileName);
      if (Files.isRegularFile(candidate)) {
        return Optional.of(candidate.toAbsolutePath().normalize());
      }
    }
    return extractEmbedded(baseName, osName, osArch());
  }

  private Optional<Path> extractEmbedded(String baseName, String osName, String osArch) {
    String fileName = baseName + "." + suffix(osName);
    String resourcePath = "native/" + platformKey(osName, osArch) + "/" + fileName;
    Path target = pathResolver.resolve().runtimeDir().resolve(fileName);
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        log.debug("No embedded Tree-sitter library at {} for this platform.", resourcePath);
        return Optional.empty();
      }
      return Optional.of(extract(in, target));
    } catch (IOException e) {
      log.warn("Could not extract embedded Tree-sitter library to {} ({}); that language is skipped.",
        target, e.toString());
      return Optional.empty();
    }
  }

  /** Copy {@code in} to {@code target} (skip when content-identical), returning the absolute path. */
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
    return (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x86_64";
  }

  private static String suffix(String osName) {
    return switch (osToken(osName)) {
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
      CodeSource source = TreeSitterLibraryResolver.class.getProtectionDomain().getCodeSource();
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
