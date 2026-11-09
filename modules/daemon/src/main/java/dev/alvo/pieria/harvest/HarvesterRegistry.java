package dev.alvo.pieria.harvest;

import dev.alvo.pieria.harvester.manifest.HarvesterManifest;
import dev.alvo.pieria.harvester.manifest.ManifestException;
import dev.alvo.pieria.harvester.manifest.ManifestParser;
import dev.alvo.pieria.tools.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The harvesters installed on disk. Every call reads the disk, so adding or removing one needs no
 * restart, and a broken harvester is logged and skipped so it can't hide the others.
 */
@Component
public class HarvesterRegistry {

  private static final Logger log = LoggerFactory.getLogger(HarvesterRegistry.class);
  private static final String ID_REGEX = "^[a-z0-9][a-z0-9_-]{0,63}$";
  private static final String MANIFEST = "harvester.toml";

  private final HarvestPaths paths;
  private final HarvestPins pins;

  public HarvesterRegistry(HarvestPaths paths) {
    this.paths = paths;
    this.pins = new HarvestPins(paths);
  }

  public List<RegisteredHarvester> list() {
    List<RegisteredHarvester> harvesters = new ArrayList<>();
    BundledHarvesters.IDS.forEach(id -> bundled(id).ifPresent(harvesters::add));
    if (!Files.isDirectory(paths.root())) {
      return List.copyOf(harvesters);
    }
    try (Stream<Path> directories = Files.list(paths.root())) {
      directories.filter(Files::isDirectory)
        .map(directory -> directory.getFileName().toString())
        .filter(name -> !BundledHarvesters.isBundled(name))
        .sorted()
        .forEach(name -> find(name).ifPresent(harvesters::add));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list harvesters", e);
    }
    return List.copyOf(harvesters);
  }

  public Optional<RegisteredHarvester> find(String id) {
    if (id == null || !id.matches(ID_REGEX)) {
      return Optional.empty();
    }
    if (BundledHarvesters.isBundled(id)) {
      return bundled(id);
    }
    Path directory = paths.harvesterDir(id);
    Path manifestFile = directory.resolve(MANIFEST);
    if (!Files.isRegularFile(manifestFile)) {
      return Optional.empty();
    }
    try {
      HarvesterManifest manifest = ManifestParser.load(manifestFile);
      if (!manifest.id().equals(id)) {
        log.warn("harvester directory {} holds a manifest for {}; skipping", id, manifest.id());
        return Optional.empty();
      }
      RegisteredHarvester harvester = new RegisteredHarvester(manifest, directory, false);
      requirePinned(harvester);
      return Optional.of(harvester);
    } catch (ManifestException | HarvesterIntegrityException e) {
      log.warn("skipping harvester {}: {}", id, e.getMessage());
      return Optional.empty();
    }
  }

  /** Empty when the binary isn't there, as in a build that ran no native step. */
  private Optional<RegisteredHarvester> bundled(String id) {
    RegisteredHarvester harvester = new RegisteredHarvester(BundledHarvesters.manifest(id), paths.bundledBin(), true);
    return binaryExists(harvester) ? Optional.of(harvester) : Optional.empty();
  }

  private static boolean binaryExists(RegisteredHarvester harvester) {
    Path binary = harvester.manifest().entrypointFile(harvester.directory());
    return Files.isRegularFile(binary) || Files.isRegularFile(binary.resolveSibling(binary.getFileName() + ".exe"));
  }

  /**
   * Confirms the entrypoint still matches its pin. Run immediately before every launch, because the
   * file can change after {@link #find}.
   */
  public void requirePinned(RegisteredHarvester harvester) {
    String id = harvester.manifest().id();
    if (harvester.bundled()) {
      // An update replaces it along with the daemon, so it carries the daemon's trust and has no pin.
      if (!binaryExists(harvester)) {
        throw new HarvesterIntegrityException("the binary of bundled harvester " + id + " is missing from " + harvester.directory());
      }
      return;
    }
    JsonNode pin = pins.read().path(id);
    String expectedEntrypoint = pin.path("entrypoint").asString("");
    String expectedManifest = pin.path("manifest").asString("");
    if (expectedEntrypoint.isEmpty() || expectedManifest.isEmpty()) {
      throw new HarvesterIntegrityException("harvester " + id + " has no pinned checksum");
    }
    // The manifest is pinned too, because it decides what runs and which hosts were shown at install.
    if (!matches(harvester.manifest().entrypointFile(harvester.directory()), expectedEntrypoint)
      || !matches(harvester.directory().resolve(MANIFEST), expectedManifest)) {
      throw new HarvesterIntegrityException("harvester " + id + " does not match its pinned checksum");
    }
  }

  private static boolean matches(Path file, String expected) {
    return Files.isRegularFile(file) && expected.equals(Hash.sha256Hex(file));
  }

  /** The state of every directory under the root, including the ones {@link #list} leaves out. */
  public List<HarvesterDiagnosis> diagnose() {
    List<HarvesterDiagnosis> entries = new ArrayList<>(BundledHarvesters.IDS.stream().map(this::diagnoseBundled).toList());
    if (!Files.isDirectory(paths.root())) {
      return List.copyOf(entries);
    }
    try (Stream<Path> directories = Files.list(paths.root())) {
      directories.filter(Files::isDirectory)
        .filter(directory -> !directory.getFileName().toString().startsWith("."))
        .sorted()
        .map(this::diagnose)
        .forEach(entries::add);
      return List.copyOf(entries);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list harvesters", e);
    }
  }

  private HarvesterDiagnosis diagnoseBundled(String id) {
    return bundled(id).isPresent()
      ? new HarvesterDiagnosis(id, true, "bundled")
      : new HarvesterDiagnosis(id, false, "the bundled binary is missing from " + paths.bundledBin());
  }

  private HarvesterDiagnosis diagnose(Path directory) {
    String name = directory.getFileName().toString();
    if (BundledHarvesters.isBundled(name)) {
      return new HarvesterDiagnosis(name, false, "ignored: the bundled harvester of this id takes precedence");
    }
    Path manifestFile = directory.resolve(MANIFEST);
    if (!Files.isRegularFile(manifestFile)) {
      return new HarvesterDiagnosis(name, false, "no manifest (" + MANIFEST + ")");
    }
    try {
      HarvesterManifest manifest = ManifestParser.load(manifestFile);
      if (!manifest.id().equals(name)) {
        return new HarvesterDiagnosis(name, false, "the manifest is for " + manifest.id());
      }
      requirePinned(new RegisteredHarvester(manifest, directory, false));
      String program = manifest.command().getFirst();
      if (!program.startsWith("./") && !onPath(program)) {
        return new HarvesterDiagnosis(name, false, program + " was not found on PATH");
      }
      return new HarvesterDiagnosis(name, true, "ok");
    } catch (ManifestException | HarvesterIntegrityException e) {
      return new HarvesterDiagnosis(name, false, e instanceof ManifestException ? "invalid manifest: " + e.getMessage() : e.getMessage());
    }
  }

  private static boolean onPath(String program) {
    String path = System.getenv("PATH");
    if (path == null) {
      return false;
    }
    return Arrays.stream(path.split(File.pathSeparator))
      .filter(directory -> !directory.isBlank())
      .anyMatch(directory -> Files.isExecutable(Path.of(directory, program)) || Files.isExecutable(Path.of(directory, program + ".exe")));
  }
}
