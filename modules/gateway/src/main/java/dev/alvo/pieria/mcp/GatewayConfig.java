package dev.alvo.pieria.mcp;

import dev.alvo.pieria.config.GatewayProperties;
import dev.alvo.pieria.client.ProfileClient;
import dev.alvo.pieria.mapping.ProfileResolver;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Wires the MCP stdio gateway. The whole gateway context is gateway-only — it is a standalone
 * module/jar ({@code :gateway}, {@code pieria-gateway.jar}) that does not have the daemon's
 * JDBC/Flyway/model-provider beans on its classpath, so none of the daemon's stateful beans can ever be
 * instantiated here. The gateway holds no state; each {@link MemoryTools} call forwards to the
 * daemon's REST surface via {@link ProfileClient}.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayConfig {

  @Bean
  ProfileClient daemonClient(GatewayProperties props) {
    return new ProfileClient(props.daemonUrl());
  }

  @Bean
  MemoryTools memoryTools(ProfileClient client) {
    String profile = ProfileResolver.create(Path.of("").toAbsolutePath()).resolve();
    return new MemoryTools(client, profile);
  }

  /**
   * Exposes the {@code @Tool}-annotated {@link MemoryTools} methods to the MCP server.
   */
  @Bean
  ToolCallbackProvider pieriaToolCallbacks(MemoryTools memoryTools) {
    return MethodToolCallbackProvider.builder().toolObjects(memoryTools).build();
  }
}
