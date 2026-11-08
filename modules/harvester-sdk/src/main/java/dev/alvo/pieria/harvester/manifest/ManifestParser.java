package dev.alvo.pieria.harvester.manifest;

import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads and validates {@code harvester.toml}, through the JSON tree so no reflection is needed. */
public final class ManifestParser {

  private static final String ID_REGEX = "^[a-z0-9][a-z0-9_-]{0,63}$";
  private static final TomlMapper TOML = TomlMapper.builder().build();

  private ManifestParser() {
  }

  public static HarvesterManifest load(Path file) {
    try {
      return parse(Files.readString(file));
    } catch (IOException e) {
      throw new ManifestException("cannot read manifest " + file + ": " + e.getMessage(), e);
    }
  }

  public static HarvesterManifest parse(String toml) {
    JsonNode root;
    try {
      root = TOML.readTree(toml);
    } catch (JacksonException e) {
      throw new ManifestException("manifest is not valid TOML: " + e.getOriginalMessage(), e);
    }

    String id = requiredString(root, "id");
    if (!id.matches(ID_REGEX)) {
      throw new ManifestException("invalid id: " + id);
    }
    JsonNode protocol = root.get("protocol");
    if (protocol == null || !protocol.isIntegralNumber()) {
      throw new ManifestException("protocol must be an integer");
    }
    if (protocol.intValue() != HarvestRequest.PROTOCOL_VERSION) {
      throw new ManifestException("unsupported protocol version: " + protocol.intValue());
    }

    Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
    stringList(root, "capabilities").forEach(capability -> capabilities.add(Capability.fromWire(capability)));

    return new HarvesterManifest(id, requiredString(root, "name"), requiredString(root, "version"), protocol.intValue(),
      command(root), capabilities, stringList(root, "network"), configFields(root));
  }

  private static List<String> command(JsonNode root) {
    JsonNode node = root.get("command");
    if (node == null || !node.isArray() || node.isEmpty()) {
      throw new ManifestException("command must be a non-empty array of strings");
    }
    List<String> command = stringList(root, "command");
    if (command.stream().anyMatch(String::isBlank)) {
      throw new ManifestException("command entries must not be blank");
    }
    requireSafeProgram(command.getFirst());
    return command;
  }

  /**
   * A program is a bare name or a {@code ./} path inside the harvester's directory. Anything else
   * would make the executable that install pinned differ from the one that runs.
   */
  private static void requireSafeProgram(String program) {
    boolean bare = !program.contains("/");
    boolean local = program.startsWith("./");
    boolean escapes = Arrays.stream(program.split("/")).anyMatch(segment -> segment.equals(".."));
    if (program.contains("\\") || program.equals(".") || escapes || !(bare || local)) {
      throw new ManifestException("command program must be a bare name or a ./ path inside the harvester: " + program);
    }
  }

  private static List<ConfigFieldSpec> configFields(JsonNode root) {
    JsonNode node = root.get("config");
    if (node == null) {
      return List.of();
    }
    if (!node.isArray()) {
      throw new ManifestException("config must be an array of tables");
    }
    List<ConfigFieldSpec> fields = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (JsonNode field : node) {
      ConfigFieldSpec spec = configField(field);
      if (!keys.add(spec.key())) {
        throw new ManifestException("duplicate config key: " + spec.key());
      }
      fields.add(spec);
    }
    return fields;
  }

  private static ConfigFieldSpec configField(JsonNode field) {
    String key = requiredString(field, "key");
    FieldKind kind = FieldKind.fromWire(requiredString(field, "kind"));
    List<String> options = stringList(field, "options");
    JsonNode defaultValue = field.get("default");
    if (kind == FieldKind.ENUM && options.isEmpty()) {
      throw new ManifestException("enum field needs options: " + key);
    }
    if (kind == FieldKind.SECRET && defaultValue != null) {
      throw new ManifestException("secret field can't have a default: " + key);
    }
    JsonNode required = field.get("required");
    if (required != null && !required.isBoolean()) {
      throw new ManifestException("required must be true or false: " + key);
    }
    return new ConfigFieldSpec(key, kind, requiredString(field, "label"),
      optionalString(field, "hint"), required != null && required.booleanValue(), options, defaultValue);
  }

  private static String requiredString(JsonNode node, String field) {
    String value = optionalString(node, field);
    if (value == null || value.isBlank()) {
      throw new ManifestException("missing required key: " + field);
    }
    return value;
  }

  private static String optionalString(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) {
      return null;
    }
    if (!value.isString()) {
      throw new ManifestException("key must be a string: " + field);
    }
    return value.asString();
  }

  private static List<String> stringList(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new ManifestException("key must be an array of strings: " + field);
    }
    List<String> values = new ArrayList<>();
    for (JsonNode item : value) {
      if (!item.isString()) {
        throw new ManifestException("key must be an array of strings: " + field);
      }
      values.add(item.asString());
    }
    return values;
  }
}
