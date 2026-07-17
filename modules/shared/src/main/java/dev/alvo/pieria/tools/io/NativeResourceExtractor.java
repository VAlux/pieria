package dev.alvo.pieria.tools.io;

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
 * Resolves and extracts embedded native shared libraries (e.g. Tree-sitter grammars, the
 * sqlite-vec loadable extension) to an on-disk runtime directory, keyed by OS/arch.
 */
public final class NativeResourceExtractor {

  private NativeResourceExtractor() {
  }

  public static String osToken(String osName) {
    String os = osName.toLowerCase(Locale.ROOT);
    if (os.contains("mac") || os.contains("darwin")) {
      return "macos";
    }
    if (os.contains("win")) {
      return "windows";
    }
    return "linux";
  }

  public static String archToken(String osArch) {
    String arch = osArch.toLowerCase(Locale.ROOT);
    return (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x86_64";
  }

  /**
   * Canonical {@code <os>-<arch>} key matching the embedded resource and packaging layout.
   */
  public static String platformKey(String osName, String osArch) {
    return osToken(osName) + "-" + archToken(osArch);
  }

  /**
   * Native shared-library suffix for {@code osName}: {@code dylib}/{@code dll}/{@code so}.
   */
  public static String librarySuffix(String osName) {
    return switch (osToken(osName)) {
      case "macos" -> "dylib";
      case "windows" -> "dll";
      default -> "so";
    };
  }

  public static Optional<Path> existingFile(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    Path path = Path.of(value);
    return Files.isRegularFile(path) ? Optional.of(path.toAbsolutePath().normalize()) : Optional.empty();
  }

  /**
   * Copy {@code in} to {@code target} (skip when content-identical), returning the absolute path.
   */
  public static Path extract(InputStream in, Path target) throws IOException {
    byte[] bytes = in.readAllBytes();
    FileOps.ensureParentDirectory(target);
    if (!Files.isRegularFile(target) || !sha256Matches(target, bytes)) {
      Files.write(target, bytes);
    }
    return target.toAbsolutePath().normalize();
  }

  public static boolean sha256Matches(Path file, byte[] expected) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] onDisk = md.digest(Files.readAllBytes(file));
      return Arrays.equals(onDisk, md.digest(expected));
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Directory holding {@code anchor}'s code (native-image executable dir, or boot-jar dir).
   */
  public static Path codeSourceDirectory(Class<?> anchor) {
    try {
      CodeSource source = anchor.getProtectionDomain().getCodeSource();
      if (source == null || source.getLocation() == null) {
        return null;
      }
      Path location = Path.of(source.getLocation().toURI());
      return Files.isDirectory(location) ? location : location.getParent();
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Directories to search for a sidecar native library, derived from {@code anchor}'s code
   * location: the code dir itself, its parent, and a sibling {@code lib} dir.
   */
  public static List<Path> installCandidateDirectories(Class<?> anchor) {
    List<Path> dirs = new ArrayList<>();
    Path codeLocation = codeSourceDirectory(anchor);
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
}
