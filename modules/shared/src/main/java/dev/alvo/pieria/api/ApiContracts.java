package dev.alvo.pieria.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Discovers every HTTP contract DTO in {@code dev.alvo.pieria.api.request} and
 * {@code dev.alvo.pieria.api.response} so native-image reflection can be registered for all of them
 * without a hand-maintained list. Used by {@link ApiContractFeature} at image-build time; the
 * discovery is plain JVM code and is unit-tested.
 *
 * <p>Discovery anchors on the code source of this class — the {@code :shared} jar (or exploded
 * classes dir in tests), which also contains the two scanned subpackages — then enumerates every
 * {@code .class} file directly under each package path. Nested records compile to
 * {@code Outer$Inner.class} in the same directory, so they are picked up without recursion.
 * Iterating jar entries by prefix (rather than {@code getResources(pkgPath)}) means jars that omit
 * explicit directory entries are still handled.
 */
public final class ApiContracts {

  private static final List<String> PACKAGES = List.of(
    "dev.alvo.pieria.api.request",
    "dev.alvo.pieria.api.response");

  private static final String CLASS_SUFFIX = ".class";

  private ApiContracts() {
  }

  /** All contract classes (top-level and nested) in the scanned packages, loaded via {@code cl}. */
  public static Set<Class<?>> all(ClassLoader cl) {
    Path root = codeSourceRoot();
    Set<String> binaryNames = new LinkedHashSet<>();
    for (String pkg : PACKAGES) {
      String pkgPath = pkg.replace('.', '/');
      if (Files.isDirectory(root)) {
        collectFromDirectory(root.resolve(pkgPath), pkg, binaryNames);
      } else {
        collectFromJar(root, pkgPath, binaryNames);
      }
    }
    Set<Class<?>> classes = new LinkedHashSet<>();
    for (String name : binaryNames) {
      try {
        classes.add(Class.forName(name, false, cl));
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException("Discovered contract class is not loadable: " + name, e);
      }
    }
    return classes;
  }

  private static Path codeSourceRoot() {
    ProtectionDomain domain = ApiContracts.class.getProtectionDomain();
    CodeSource codeSource = domain == null ? null : domain.getCodeSource();
    URL location = codeSource == null ? null : codeSource.getLocation();
    if (location == null) {
      throw new IllegalStateException(
        "Cannot determine the code source of " + ApiContracts.class.getName()
          + "; api contract discovery needs it to enumerate the request/response packages.");
    }
    try {
      return Path.of(location.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException("Cannot resolve code source location " + location, e);
    }
  }

  private static void collectFromDirectory(Path packageDir, String pkg, Set<String> out) {
    if (!Files.isDirectory(packageDir)) {
      return;
    }
    try (Stream<Path> entries = Files.list(packageDir)) {
      entries
        .map(p -> p.getFileName().toString())
        .filter(name -> name.endsWith(CLASS_SUFFIX))
        .sorted()
        .forEach(name -> out.add(pkg + '.' + stripSuffix(name)));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to scan package directory " + packageDir, e);
    }
  }

  private static void collectFromJar(Path jar, String pkgPath, Set<String> out) {
    String prefix = pkgPath + '/';
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      jarFile.stream()
        .map(JarEntry::getName)
        .filter(name -> name.startsWith(prefix)
          && name.endsWith(CLASS_SUFFIX)
          && name.indexOf('/', prefix.length()) < 0)
        .sorted()
        .forEach(name -> out.add(stripSuffix(name).replace('/', '.')));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to scan jar " + jar, e);
    }
  }

  private static String stripSuffix(String classFileName) {
    return classFileName.substring(0, classFileName.length() - CLASS_SUFFIX.length());
  }
}
