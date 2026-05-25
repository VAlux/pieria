package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Storage-backend selector. The current implementation ships embedded SQLite only, but status output exposes the
 * configured backend for future server-mode extensions.
 */
@ConfigurationProperties(prefix = "pieria.storage")
public record StorageProperties(@DefaultValue("sqlite") String backend) {
}
