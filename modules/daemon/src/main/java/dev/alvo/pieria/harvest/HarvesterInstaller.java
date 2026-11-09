package dev.alvo.pieria.harvest;

import dev.alvo.pieria.domain.error.ConflictException;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.harvester.manifest.HarvesterManifest;
import dev.alvo.pieria.harvester.manifest.ManifestException;
import dev.alvo.pieria.harvester.manifest.ManifestParser;
import dev.alvo.pieria.tools.Hash;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Installs harvester packages under the harvesters root. Everything is prepared in a staging directory
 * first, so a rejected or failed package leaves the installed harvesters exactly as they were.
 */
@Component
public class HarvesterInstaller {

  private static final String MANIFEST = "harvester.toml";
  private static final String MAC_METADATA = "__MACOSX";
  private static final String ID_REGEX = "^[a-z0-9][a-z0-9_-]{0,63}$";
  private static final int MAX_FILES = 20_000;
  private static final long MAX_UNPACKED_BYTES = 256L * 1024 * 1024;

  private final HarvestPaths paths;
  private final HarvestStore store;
  private final HarvestPins pins;

  public HarvesterInstaller(HarvestPaths paths, HarvestStore store) {
    this.paths = paths;
    this.store = store;
    this.pins = new HarvestPins(paths);
  }

  /** Reads a package without installing it, so its contents can be shown first. */
  public HarvesterPackage inspect(Path source) {
    Path staging = newWorkingDirectory(".staging-");
    try {
      return describe(unpack(source, staging));
    } finally {
      deleteTree(staging);
    }
  }

  /**
   * Installs a package and pins it. Supplying the hashes from {@link #inspect} ensures what is installed
   * is what was shown.
   */
  public synchronized HarvesterPackage install(Path source, String expectedEntrypointSha256, String expectedManifestSha256,
                                               boolean replace) {
    Path staging = newWorkingDirectory(".staging-");
    try {
      Path packageRoot = unpack(source, staging);
      HarvesterPackage described = describe(packageRoot);
      if ((expectedEntrypointSha256 != null && !expectedEntrypointSha256.equals(described.entrypointSha256()))
        || (expectedManifestSha256 != null && !expectedManifestSha256.equals(described.manifestSha256()))) {
        throw new ConflictException("the package changed after it was inspected");
      }
      String id = described.manifest().id();
      Path target = paths.harvesterDir(id);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !replace) {
        throw new ConflictException("harvester " + id + " is already installed");
      }
      makeProgramExecutable(described.manifest(), packageRoot);
      swapIn(packageRoot, target, described);
      return described;
    } finally {
      deleteTree(staging);
    }
  }

  public synchronized void remove(String id) {
    if (id == null || !id.matches(ID_REGEX) || !Files.isDirectory(paths.harvesterDir(id), LinkOption.NOFOLLOW_LINKS)) {
      throw new NotFoundException("harvester not installed: " + id);
    }
    if (store.anySourceUses(id)) {
      throw new ConflictException("harvester " + id + " is used by a source; remove the source first");
    }
    pins.remove(id);
    deleteTree(paths.harvesterDir(id));
  }

  private void swapIn(Path packageRoot, Path target, HarvesterPackage described) {
    Path backup = paths.root().resolve(".old-" + UUID.randomUUID());
    boolean replacing = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
    try {
      if (replacing) {
        Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
      }
      try {
        Files.move(packageRoot, target, StandardCopyOption.ATOMIC_MOVE);
        pins.put(described.manifest().id(), described.entrypointSha256(), described.manifestSha256());
      } catch (IOException | RuntimeException e) {
        deleteTree(target);
        if (replacing) {
          Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
        }
        throw e;
      }
      if (replacing) {
        deleteTree(backup);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot install the harvester", e);
    }
  }

  private Path newWorkingDirectory(String prefix) {
    try {
      Files.createDirectories(paths.root());
      return Files.createDirectory(paths.root().resolve(prefix + UUID.randomUUID()));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot prepare the harvesters directory", e);
    }
  }

  private static Path unpack(Path source, Path staging) {
    if (!source.isAbsolute()) {
      throw new IllegalArgumentException("the package path must be absolute: " + source);
    }
    try {
      if (Files.isDirectory(source)) {
        copyDirectory(source, staging);
        return staging;
      }
      if (Files.isRegularFile(source) && source.getFileName().toString().endsWith(".zip")) {
        unzip(source, staging);
        return packageRootOf(staging);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the package", e);
    }
    throw new IllegalArgumentException("the package must be a directory or a .zip file: " + source);
  }

  private static void copyDirectory(Path source, Path staging) throws IOException {
    long[] counts = {0, 0};
    Files.walkFileTree(source, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        Files.createDirectories(staging.resolve(source.relativize(dir).toString()));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (!attrs.isRegularFile()) {
          throw new IllegalArgumentException("the package may only contain regular files: " + source.relativize(file));
        }
        requireWithinLimits(++counts[0], counts[1] += attrs.size());
        Files.copy(file, staging.resolve(source.relativize(file).toString()),
          StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private static void requireWithinLimits(long files, long bytes) {
    if (files > MAX_FILES) {
      throw new IllegalArgumentException("the package has more than " + MAX_FILES + " files");
    }
    if (bytes > MAX_UNPACKED_BYTES) {
      throw new IllegalArgumentException("the package is larger than " + MAX_UNPACKED_BYTES + " bytes unpacked");
    }
  }

  /** Bytes are counted as they are written, because the sizes a zip declares can't be trusted. */
  private static void unzip(Path source, Path staging) throws IOException {
    long files = 0;
    long bytes = 0;
    byte[] buffer = new byte[8192];
    try (InputStream raw = Files.newInputStream(source); ZipInputStream zip = new ZipInputStream(raw)) {
      boolean any = false;
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        any = true;
        Path target = safeEntryPath(staging, entry.getName());
        if (entry.isDirectory()) {
          Files.createDirectories(target);
          continue;
        }
        requireWithinLimits(++files, bytes);
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target)) {
          for (int read = zip.read(buffer); read != -1; read = zip.read(buffer)) {
            requireWithinLimits(files, bytes += read);
            out.write(buffer, 0, read);
          }
        }
      }
      if (!any) {
        throw new IllegalArgumentException("the file is not a valid zip archive");
      }
    } catch (ZipException e) {
      throw new IllegalArgumentException("the file is not a valid zip archive", e);
    } catch (IOException e) {
      // A truncated archive or entries that collide; the staging path in the cause means nothing to the user.
      throw new IllegalArgumentException("the zip could not be unpacked", e);
    }
  }

  private static Path safeEntryPath(Path staging, String name) {
    boolean unsafe = name.startsWith("/") || name.contains("\\") || name.matches("^[A-Za-z]:.*")
      || Arrays.asList(name.split("/")).contains("..");
    Path target = staging.resolve(name).normalize();
    if (unsafe || !target.startsWith(staging)) {
      throw new IllegalArgumentException("the zip contains an unsafe path: " + name);
    }
    return target;
  }

  /** A zip may hold the package at its root, or inside one top-level directory. Finder adds a metadata folder beside it. */
  private static Path packageRootOf(Path staging) throws IOException {
    if (Files.isRegularFile(staging.resolve(MANIFEST))) {
      return staging;
    }
    try (Stream<Path> entries = Files.list(staging)) {
      List<Path> top = entries.filter(entry -> !entry.getFileName().toString().equals(MAC_METADATA)).toList();
      return top.size() == 1 && Files.isDirectory(top.getFirst()) ? top.getFirst() : staging;
    }
  }

  private static HarvesterPackage describe(Path packageRoot) {
    Path manifestFile = packageRoot.resolve(MANIFEST);
    if (!Files.isRegularFile(manifestFile)) {
      throw new IllegalArgumentException("the package has no " + MANIFEST);
    }
    HarvesterManifest manifest;
    try {
      manifest = ManifestParser.load(manifestFile);
    } catch (ManifestException e) {
      throw new IllegalArgumentException("invalid manifest: " + e.getMessage(), e);
    }
    Path entrypoint = manifest.entrypointFile(packageRoot);
    if (!Files.isRegularFile(entrypoint)) {
      throw new IllegalArgumentException("the package has no entrypoint " + manifest.entrypoint());
    }
    return new HarvesterPackage(manifest, Hash.sha256Hex(entrypoint), Hash.sha256Hex(manifestFile));
  }

  /** A zip doesn't reliably carry the executable bit. */
  private static void makeProgramExecutable(HarvesterManifest manifest, Path packageRoot) {
    if (manifest.command().getFirst().startsWith("./")) {
      Path program = packageRoot.resolve(manifest.command().getFirst()).normalize();
      if (!Files.isRegularFile(program)) {
        throw new IllegalArgumentException("the package has no program " + manifest.command().getFirst());
      }
      program.toFile().setExecutable(true, true);
    }
  }

  private static void deleteTree(Path root) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
          if (failure != null) {
            throw failure;
          }
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      throw new UncheckedIOException("cannot remove " + root, e);
    }
  }
}
