package dev.alvo.pieria.gateway;

import dev.alvo.pieria.config.GatewayProperties;
import dev.alvo.pieria.mcp.GatewayConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * Entry point for the Pieria MCP stdio gateway — a stateless stdio MCP server that forwards model
 * tool calls to the local daemon's REST surface.
 *
 * <p>Running this jar IS the gateway: there is no launch flag and no Spring profile. The gateway
 * module simply does not have JDBC/Flyway/model-provider beans on its classpath, so none of the daemon's
 * infrastructure is ever instantiated. Web/banner/logging/MCP settings live in this module's
 * {@code application.properties}.
 */
@SpringBootApplication
@ComponentScan(basePackages = "dev.alvo.pieria.mcp")
@EnableConfigurationProperties(GatewayProperties.class)
@Import(GatewayConfig.class)
public class GatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
