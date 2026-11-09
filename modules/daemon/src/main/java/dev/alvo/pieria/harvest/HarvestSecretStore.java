package dev.alvo.pieria.harvest;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Source credentials, in one file readable only by the user. Replaced atomically, and never overwritten
 * when unreadable, because a bad write here would lose every source's credentials.
 */
@Component
public class HarvestSecretStore {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HarvestPaths paths;

  public HarvestSecretStore(HarvestPaths paths) {
    this.paths = paths;
  }

  public synchronized Map<String, String> secrets(String sourceId) {
    Map<String, String> secrets = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : read().path(sourceId).properties()) {
      secrets.put(entry.getKey(), entry.getValue().asString());
    }
    return secrets;
  }

  public synchronized void put(String sourceId, String name, String value) {
    ObjectNode all = read();
    ObjectNode source = all.get(sourceId) instanceof ObjectNode existing ? existing : all.putObject(sourceId);
    source.put(name, value);
    write(all);
  }

  public synchronized void remove(String sourceId) {
    ObjectNode all = read();
    if (all.remove(sourceId) != null) {
      write(all);
    }
  }

  private ObjectNode read() {
    if (!Files.isRegularFile(paths.secretsFile())) {
      return MAPPER.createObjectNode();
    }
    try {
      if (MAPPER.readTree(Files.readString(paths.secretsFile())) instanceof ObjectNode object) {
        return object;
      }
    } catch (JacksonException e) {
      // Reported below without the exception, whose message could quote the file.
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read harvester secrets", e);
    }
    throw new IllegalStateException("harvester secrets file is corrupt: " + paths.secretsFile());
  }

  private void write(ObjectNode all) {
    try {
      Files.createDirectories(paths.root());
      Path temporary = Files.createTempFile(paths.root(), "secrets", ".tmp", privateFile());
      Files.writeString(temporary, all.toString());
      Files.move(temporary, paths.secretsFile(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write harvester secrets", e);
    }
  }

  private static FileAttribute<?>[] privateFile() {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))};
    }
    return new FileAttribute<?>[0];
  }
}
