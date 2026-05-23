package dev.alvo.pieria.api;

import org.springframework.context.annotation.Profile;

import dev.alvo.pieria.api.response.HealthResponse;
import dev.alvo.pieria.model.ModelGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Lightweight health endpoint for the Pieria daemon (SPEC 14, Phase 4 step 1).
 *
 * <p>{@code GET /healthz} returns an overall {@code status} plus two subsystem indicators:
 * <ul>
 *   <li><b>db</b> — a {@link Connection#isValid(int)} probe against the embedded store; no
 *       schema details or row counts are disclosed.</li>
 *   <li><b>modelProvider</b> — a cheap reachability check via
 *       {@link ModelGateway#isModelProviderReachable()} that never invokes a model or generates
 *       tokens. Provider hostnames are never included in the response.</li>
 * </ul>
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
@Profile("!shim")
@RequestMapping("/healthz")
public class HealthController {

  private final DataSource dataSource;
  private final ModelGateway modelGateway;

  public HealthController(DataSource dataSource, ModelGateway modelGateway) {
    this.dataSource = dataSource;
    this.modelGateway = modelGateway;
  }

  @GetMapping
  public ResponseEntity<HealthResponse> health() {
    String dbStatus = probeDb();
    String modelStatus = probeModel();

    boolean dbOk = "ok".equals(dbStatus);
    String overallStatus = dbOk ? "up" : "degraded";

    HealthResponse body = new HealthResponse(overallStatus, dbStatus, modelStatus);
    return dbOk
      ? ResponseEntity.ok(body)
      : ResponseEntity.status(503).body(body);
  }

  /** {@link Connection#isValid(int)} probe with a 2-second timeout. */
  private String probeDb() {
    try (Connection conn = dataSource.getConnection()) {
      return conn.isValid(2) ? "ok" : "down";
    } catch (Exception e) {
      return "down";
    }
  }

  /** Delegates to {@link ModelGateway#isModelProviderReachable()} — no model calls. */
  private String probeModel() {
    try {
      return modelGateway.isModelProviderReachable() ? "reachable" : "unreachable";
    } catch (Exception e) {
      return "unknown";
    }
  }
}
