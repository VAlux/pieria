package dev.alvo.pieria.harvester.conformance;

import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Map;

/**
 * How to drive the harvester. Point {@code config} at a fixture or stub, because several checks need
 * the source to stay the same between runs.
 *
 * @param invalidConfig config that {@code check} must reject; null skips that check
 * @param timeout       the limit for each run of the harvester
 */
public record ConformanceOptions(ObjectNode config, Map<String, String> secrets, ObjectNode invalidConfig,
                                 Duration timeout) {

  public ConformanceOptions {
    secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
  }
}
