package dev.alvo.pieria.api.response;

/**
 * Accepted restart: the daemon exits shortly after answering and {@code mechanism} (launchd or
 * systemd) starts it again; clients should poll {@code /pieria-health} until it answers.
 */
public record DaemonRestartResponse(String mechanism, long expectedDowntimeMs) {
}
