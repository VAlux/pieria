package dev.alvo.pieria.config.schema;

import dev.alvo.pieria.config.toml.ConfigCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the editable-configuration schema once at construction. The schema carries the human copy
 * (labels, hints, grouping); the KEYS are checked against {@code DaemonOverrides} by
 * {@code ConfigSchemaTests}, so the console can never offer a field the daemon would reject.
 */
@Component
public class ConfigSchemaService {

  static final String SCHEMA_RESOURCE = "config/config-schema.json";

  private final List<ConfigField> fields;
  private final Map<String, ConfigField> byKey;

  public ConfigSchemaService() {
    this.fields = List.copyOf(load());
    Map<String, ConfigField> index = new LinkedHashMap<>();
    for (ConfigField field : fields) {
      index.put(field.key(), field);
    }
    this.byKey = Map.copyOf(index);
  }

  private static List<ConfigField> load() {
    try (InputStream in = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
      String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      JsonNode root = ConfigCodec.parseJson(json);
      List<ConfigField> parsed = new ArrayList<>();
      for (JsonNode node : root) {
        parsed.add(ConfigCodec.bind(node, ConfigField.class));
      }
      return parsed;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + SCHEMA_RESOURCE, e);
    }
  }

  /**
   * Every editable field, in declaration order.
   */
  public List<ConfigField> all() {
    return fields;
  }

  /**
   * Fields for one scope: {@code profile} or {@code global}.
   */
  public List<ConfigField> forScope(String scope) {
    return fields.stream().filter(field -> field.scope().equals(scope)).toList();
  }

  public Optional<ConfigField> find(String key) {
    return Optional.ofNullable(byKey.get(key));
  }
}
