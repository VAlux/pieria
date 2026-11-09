package dev.alvo.pieria.harvest;

import dev.alvo.pieria.config.AppDataPathResolver;
import dev.alvo.pieria.config.HarvestProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.alvo.pieria.tools.os.InstallHome;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class HarvestConfig {

  @Bean
  HarvestPaths harvestPaths(HarvestProperties properties, AppDataPathResolver resolver) {
    return new HarvestPaths(properties.root().isBlank()
      ? resolver.resolve().root().resolve("harvesters")
      : Path.of(properties.root()),
      properties.bundledBin().isBlank() ? InstallHome.ofDaemon().resolve("bin") : Path.of(properties.bundledBin()));
  }
}
