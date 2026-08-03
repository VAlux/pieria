package dev.alvo.pieria.audit;

/**
 * Best-effort caller identity declared through stable Pieria HTTP headers.
 */
public record AuditCaller(String client, String harness, String channel, String version, String remoteAddress) {
}
