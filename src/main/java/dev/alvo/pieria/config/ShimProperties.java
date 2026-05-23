package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Config for the MCP stdio shim launch mode (SPEC 10.1, phase-4 step 3). Kept as a separate
 * top-level {@code @ConfigurationProperties} (rather than a nested {@link PieriaProperties}
 * component) so the daemon's existing positional constructor calls in tests stay untouched.
 *
 * <p>{@code daemonUrl} defaults to the daemon's bound host/port so the shim points at the local
 * daemon out of the box; override with {@code pieria.shim.daemon-url}.
 */
@ConfigurationProperties(prefix = "pieria.shim")
public record ShimProperties(
  @DefaultValue("http://${pieria.daemon.host:127.0.0.1}:${pieria.daemon.port:8077}") String daemonUrl) {
}
