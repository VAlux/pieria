package dev.alvo.pieria.mcp;

import dev.alvo.pieria.config.ShimProperties;
import dev.alvo.pieria.mapping.ProfileResolver;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;

import tools.jackson.databind.ObjectMapper;

/**
 * Wires the MCP stdio shim. Active only under the {@code shim} profile so the daemon process never
 * instantiates these beans, and conversely the daemon's component-scanned beans (controllers,
 * store, datasource, ingestion/retrieval/model) are kept out of the shim process by
 * {@code @Profile("!shim")} guards on their declarations plus DB/Flyway autoconfig exclusions and
 * {@code spring.main.web-application-type=none} (see {@code application-shim.properties} and
 * {@code PieriaApplication}). The result: one jar, two launch modes, no shared beans beyond config.
 */
@Configuration
@Profile("shim")
@EnableConfigurationProperties(ShimProperties.class)
public class ShimConfig {

  @Bean
  DaemonClient daemonClient(ShimProperties props) {
    return new DaemonClient(props.daemonUrl());
  }

  @Bean
  MemoryTools memoryTools(DaemonClient client, ObjectMapper objectMapper) {
    String profile = ProfileResolver.create(Path.of("").toAbsolutePath()).resolve();
    return new MemoryTools(client, profile, objectMapper);
  }

  /** Exposes the {@code @Tool}-annotated {@link MemoryTools} methods to the MCP server. */
  @Bean
  ToolCallbackProvider pieriaToolCallbacks(MemoryTools memoryTools) {
    return MethodToolCallbackProvider.builder().toolObjects(memoryTools).build();
  }
}
