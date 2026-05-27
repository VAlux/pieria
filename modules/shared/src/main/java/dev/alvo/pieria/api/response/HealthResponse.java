package dev.alvo.pieria.api.response;

/**
 * Response body for {@code GET /pieria-health}.
 *
 * <ul>
 *   <li>{@code status} — {@code "up"} when all subsystems are healthy; {@code "degraded"} when
 *       the DB check fails (the daemon is still running but cannot serve requests reliably).</li>
 *   <li>{@code db} — {@code "ok"} or {@code "down"}; determined by a trivial {@code SELECT 1}
 *       probe; never exposes schema details.</li>
 *   <li>{@code modelProvider} — {@code "reachable"}, {@code "unreachable"}, or
 *       {@code "unknown"} (used by stubs / configs where no probe is available). The provider
 *       hostname is never included here.</li>
 * </ul>
 *
 * <p>Localhost-only note: this endpoint is intended for local-mode use only. In local mode the
 * daemon binds {@code 127.0.0.1} and the health surface is not exposed to the network.
 */
public record HealthResponse(String status, String db, String modelProvider) {
}
