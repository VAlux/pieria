package dev.alvo.pieria.shim;

import dev.alvo.pieria.config.ShimProperties;
import dev.alvo.pieria.mcp.ShimConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * Entry point for the Pieria MCP stdio shim — a stateless stdio MCP server that forwards model tool
 * calls to the local daemon's REST surface (SPEC 10.1).
 *
 * <p>Running this jar IS the shim: there is no launch flag and no Spring profile. The shim module
 * simply does not have JDBC/Flyway/Ollama on its classpath, so none of the daemon's infrastructure
 * is ever instantiated. Web/banner/logging/MCP settings live in this module's
 * {@code application.properties}.
 */
@SpringBootApplication
@ComponentScan(basePackages = "dev.alvo.pieria.mcp")
@EnableConfigurationProperties(ShimProperties.class)
@Import(ShimConfig.class)
public class ShimApplication {

  public static void main(String[] args) {
    SpringApplication.run(ShimApplication.class, args);
  }
}
