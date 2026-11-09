package dev.alvo.pieria.api.response;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * An installed harvester and the settings it asks for.
 *
 * @param bundled whether it ships with Pieria; null from a daemon too old to say
 */
public record HarvesterResponse(String id, String name, String version, List<String> capabilities, List<String> network,
                                List<Field> fields, Boolean bundled) {

  /** One setting. {@code kind} is string, secret, int, bool, enum or list. */
  public record Field(String key, String kind, String label, String hint, boolean required, List<String> options,
                      JsonNode defaultValue) {
  }
}
