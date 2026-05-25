package dev.alvo.pieria;

import dev.alvo.pieria.config.DaemonNativeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Pieria daemon — the pure-REST background service holding all state (embedded
 * SQLite store, ingestion/retrieval pipelines, model gateway).
 *
 * <p>The MCP stdio gateway is a separate module/jar ({@code :gateway}, {@code pieria-gateway.jar}); this
 * process is always the daemon.
 */
@SpringBootApplication
@ComponentScan(excludeFilters = {
  @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
  @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
  @ComponentScan.Filter(type = FilterType.REGEX, pattern = "dev\\.alvo\\.pieria\\.(mcp|gateway)\\..*")
})
@ConfigurationPropertiesScan
@EnableScheduling
@ImportRuntimeHints(DaemonNativeHints.class)
public class PieriaApplication {

  public static void main(String[] args) {
    SpringApplication.run(PieriaApplication.class, args);
  }
}
