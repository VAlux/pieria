package dev.alvo.pieria.harvester.manifest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A parsed {@code harvester.toml}.
 *
 * @param command the program and its arguments; the program is a bare name found on {@code PATH}, or
 *                a {@code ./} path inside the harvester's directory
 * @param network hosts the harvester says it contacts, shown when it is installed
 */
public record HarvesterManifest(String id, String name, String version, int protocol, List<String> command,
                                Set<Capability> capabilities, List<String> network, List<ConfigFieldSpec> config) {

  public HarvesterManifest {
    command = List.copyOf(command);
    capabilities = Set.copyOf(capabilities);
    network = List.copyOf(network);
    config = List.copyOf(config);
  }

  public boolean has(Capability capability) {
    return capabilities.contains(capability);
  }

  /** The command to launch, with a {@code ./} program made absolute against {@code manifestDir}. */
  public List<String> resolvedCommand(Path manifestDir) {
    List<String> resolved = new ArrayList<>(command);
    if (command.getFirst().startsWith("./")) {
      resolved.set(0, manifestDir.toAbsolutePath().resolve(command.getFirst()).normalize().toString());
    }
    return List.copyOf(resolved);
  }
}
