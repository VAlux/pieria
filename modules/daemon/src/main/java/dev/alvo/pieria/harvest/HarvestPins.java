package dev.alvo.pieria.harvest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** The checksums installed harvesters must match. Replaced atomically, so a crash can't truncate it. */
public final class HarvestPins {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HarvestPaths paths;

  public HarvestPins(HarvestPaths paths) {
    this.paths = paths;
  }

  public synchronized JsonNode read() {
    return readObject();
  }

  public synchronized void put(String id, String entrypointSha256, String manifestSha256) {
    ObjectNode pins = readObject();
    pins.putObject(id).put("entrypoint", entrypointSha256).put("manifest", manifestSha256);
    write(pins);
  }

  public synchronized void remove(String id) {
    ObjectNode pins = readObject();
    if (pins.remove(id) != null) {
      write(pins);
    }
  }

  private ObjectNode readObject() {
    if (!Files.isRegularFile(paths.pinsFile())) {
      return MAPPER.createObjectNode();
    }
    try {
      if (MAPPER.readTree(Files.readString(paths.pinsFile())) instanceof ObjectNode pins) {
        return pins;
      }
    } catch (IOException | JacksonException e) {
      throw new HarvesterIntegrityException("cannot read harvester pins: " + e.getMessage());
    }
    throw new HarvesterIntegrityException("harvester pins file is not a JSON object");
  }

  private void write(ObjectNode pins) {
    try {
      Files.createDirectories(paths.root());
      Path temporary = Files.createTempFile(paths.root(), ".pins", ".tmp");
      Files.writeString(temporary, pins.toString());
      Files.move(temporary, paths.pinsFile(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write harvester pins", e);
    }
  }
}
