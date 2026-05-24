package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configurable local app-data locations. Blank values are resolved by {@link AppDataPathResolver}
 * to OS-appropriate defaults so tests and advanced users can override only the paths they need.
 */
@ConfigurationProperties(prefix = "pieria.app-data")
public record AppDataProperties(
  @DefaultValue("") String root,
  @DefaultValue("") String databaseDir,
  @DefaultValue("") String configDir,
  @DefaultValue("") String logsDir,
  @DefaultValue("") String runtimeDir) {
}
