package dev.alvo.pieria.config;

import dev.alvo.pieria.config.schema.ConfigField;
import dev.alvo.pieria.config.schema.ConfigSchemaService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and writes the process-global configuration: the effective values the daemon is running
 * with, and validated edits to {@code pieria.properties} in the config directory.
 *
 * <p>Writes are all-or-nothing. Every key in a batch is validated against the schema before
 * anything reaches disk, so a single bad value cannot leave the file half-updated — the daemon
 * imports this file at startup and a partial write is a broken boot.
 *
 * <p>Locked-tier keys ({@code embedding-dimension}, {@code db.path}, {@code storage.backend})
 * require an explicit acknowledgement. That check lives here, not only in the console: changing
 * the embedding dimension invalidates every vector in the fixed-width {@code memories_vec} table,
 * and nothing else in the daemon would stop it.
 */
@Component
public class GlobalConfigService {

  private static final String PROPERTIES_FILE = "pieria.properties";

  private final ConfigSchemaService schema;
  private final Environment environment;
  private final AppDataPathResolver pathResolver;

  public GlobalConfigService(ConfigSchemaService schema,
                             Environment environment,
                             AppDataPathResolver pathResolver) {
    this.schema = schema;
    this.environment = environment;
    this.pathResolver = pathResolver;
  }

  /** What was written, what was cleared, and which of those the running daemon will not pick up. */
  public record ApplyResult(List<String> written, List<String> cleared, List<String> restartRequired) {
  }

  /** Every global-scoped key with its running value, file value and provenance. */
  public List<GlobalConfigEntry> effective() {
    PropertiesFileEditor file = PropertiesFileEditor.read(propertiesPath());
    List<GlobalConfigEntry> entries = new ArrayList<>();

    for (ConfigField field : schema.forScope("global")) {
      String running = environment.getProperty(field.key());
      String onDisk = file.get(field.key()).orElse(null);
      entries.add(new GlobalConfigEntry(
        field.key(),
        field.section(),
        field.tier(),
        field.kind(),
        field.options(),
        field.label(),
        field.hint(),
        running,
        onDisk,
        onDisk == null ? "default" : "set",
        onDisk != null && !Objects.equals(onDisk, running)));
    }
    return List.copyOf(entries);
  }

  /**
   * Apply a batch of updates. A {@code null} value clears the key so the shipped default applies
   * again. Throws {@link IllegalArgumentException} — mapped to 400 by the global handler — before
   * touching disk if any key is unknown, out of scope, locked without acknowledgement, or fails to
   * parse for its declared kind.
   */
  public ApplyResult apply(Map<String, String> updates, boolean acknowledgeDestructive) {
    if (updates == null || updates.isEmpty()) {
      return new ApplyResult(List.of(), List.of(), List.of());
    }

    for (Map.Entry<String, String> update : updates.entrySet()) {
      ConfigField field = requireGlobalField(update.getKey());
      if ("locked".equals(field.tier()) && !acknowledgeDestructive) {
        throw new IllegalArgumentException("'" + field.key()
          + "' cannot be changed in place; the request must acknowledge the consequences");
      }
      if (update.getValue() != null) {
        validate(field, update.getValue());
      }
    }

    Path path = propertiesPath();
    PropertiesFileEditor file = PropertiesFileEditor.read(path);
    List<String> written = new ArrayList<>();
    List<String> cleared = new ArrayList<>();
    List<String> restart = new ArrayList<>();

    for (Map.Entry<String, String> update : updates.entrySet()) {
      ConfigField field = requireGlobalField(update.getKey());
      if (update.getValue() == null) {
        file.remove(field.key());
        cleared.add(field.key());
      } else {
        file.set(field.key(), update.getValue());
        written.add(field.key());
      }
      if (!"live".equals(field.tier())) {
        restart.add(field.key());
      }
    }

    file.write(path);
    return new ApplyResult(List.copyOf(written), List.copyOf(cleared), List.copyOf(restart));
  }

  private ConfigField requireGlobalField(String key) {
    Optional<ConfigField> found = schema.find(key);
    if (found.isEmpty() || !"global".equals(found.get().scope())) {
      throw new IllegalArgumentException("unknown or non-overridable global config key: '" + key + "'");
    }
    return found.get();
  }

  private static void validate(ConfigField field, String value) {
    switch (field.kind()) {
      case "int" -> parseOrThrow(field, value, () -> Long.parseLong(value.trim()));
      case "double", "weight" -> parseOrThrow(field, value, () -> Double.parseDouble(value.trim()));
      case "bool" -> {
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("true") && !normalized.equals("false")) {
          throw new IllegalArgumentException("'" + field.key() + "' must be true or false, got '" + value + "'");
        }
      }
      case "enum" -> {
        if (!field.options().contains(value.trim())) {
          throw new IllegalArgumentException("'" + field.key() + "' must be one of "
            + field.options() + ", got '" + value + "'");
        }
      }
      default -> {
        // string and secret accept any value, including the empty string.
      }
    }
  }

  private static void parseOrThrow(ConfigField field, String value, Runnable parse) {
    try {
      parse.run();
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("'" + field.key() + "' must be a number, got '" + value + "'");
    }
  }

  private Path propertiesPath() {
    return pathResolver.resolve().configDir().resolve(PROPERTIES_FILE);
  }
}
