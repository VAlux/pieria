package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Storage-backend selector. Phase 5 still ships embedded SQLite only, but status output exposes the
 * configured backend so server-mode work can swap this without changing the local status contract.
 */
@ConfigurationProperties(prefix = "pieria.storage")
public record StorageProperties(@DefaultValue("sqlite") String backend) {
}
