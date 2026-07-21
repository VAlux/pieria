package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Config for the MCP stdio gateway. Kept as a separate top-level
 * {@code @ConfigurationProperties} so the daemon's existing positional constructor calls in tests
 * stay untouched.
 *
 * <p>{@code daemonUrl} defaults to the daemon's bound host/port so the gateway points at the local
 * daemon out of the box; override with {@code pieria.gateway.daemon-url} or the harness-wide
 * {@code PIERIA_DAEMON_URL} environment variable.
 */
@ConfigurationProperties(prefix = "pieria.gateway")
public record GatewayProperties(@DefaultValue("") String daemonUrl,
                                @DefaultValue("") String harness) {
  public GatewayProperties {
    if (daemonUrl == null || daemonUrl.isBlank()) {
      daemonUrl = System.getenv().getOrDefault("PIERIA_DAEMON_URL", "http://127.0.0.1:8077");
    }
    if (harness == null || harness.isBlank()) {
      harness = System.getenv().getOrDefault("PIERIA_HARNESS", "");
    }
  }
}
