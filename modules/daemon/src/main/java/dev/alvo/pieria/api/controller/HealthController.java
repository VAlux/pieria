package dev.alvo.pieria.api.controller;


import dev.alvo.pieria.api.response.HealthResponse;
import dev.alvo.pieria.health.HealthService;
import dev.alvo.pieria.health.HealthService.HealthCheck;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight health endpoint for the Pieria daemon.
 *
 * <p>{@code GET /pieria-health} returns an overall {@code status} plus two subsystem indicators
 * reported by {@link HealthService}: {@code db} and {@code modelProvider}. Provider hostnames and
 * schema details are never disclosed.
 *
 * <p>Returns HTTP 200 with {@code status:"up"} when the DB is healthy; HTTP 503 with
 * {@code status:"degraded"} when the DB check fails. Model-provider status is reported but
 * does not drive the overall status (many operations are DB-only in local mode).
 *
 * <p>Localhost-only assumption: in local mode the daemon binds {@code 127.0.0.1} only
 * (configured via {@code server.address=\${pieria.daemon.host}}), so this endpoint is not
 * reachable from the network.
 */
@RestController
@RequestMapping("/pieria-health")
public class HealthController {

  private final HealthService healthService;

  public HealthController(HealthService healthService) {
    this.healthService = healthService;
  }

  @GetMapping
  public ResponseEntity<HealthResponse> health() {
    HealthCheck check = healthService.check();
    HealthResponse body = new HealthResponse(
      check.dbOk() ? "up" : "degraded", check.dbStatus(), check.modelStatus());

    return check.dbOk()
      ? ResponseEntity.ok(body)
      : ResponseEntity.status(503).body(body);
  }
}
