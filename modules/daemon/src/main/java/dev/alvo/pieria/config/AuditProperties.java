package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Process-wide profile audit capture limits.
 */
@ConfigurationProperties(prefix = "pieria.audit")
public record AuditProperties(@DefaultValue("1048576") int maxBodyBytes) {

  public AuditProperties {
    maxBodyBytes = Math.max(0, maxBodyBytes);
  }
}
