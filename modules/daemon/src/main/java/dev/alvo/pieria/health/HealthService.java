package dev.alvo.pieria.health;

import dev.alvo.pieria.model.ModelGateway;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Cheap liveness probes for the Pieria daemon: a {@link Connection#isValid(int)} check against the
 * embedded store, and a reachability check via {@link ModelGateway#isModelProviderReachable()} that
 * never invokes a model or generates tokens.
 */
@Service
public class HealthService {

  private final DataSource dataSource;
  private final ModelGateway modelGateway;

  public HealthService(DataSource dataSource, ModelGateway modelGateway) {
    this.dataSource = dataSource;
    this.modelGateway = modelGateway;
  }

  /**
   * db/model probe result. {@code dbOk} is {@code true} only when {@code dbStatus} is {@code "ok"}.
   */
  public record HealthCheck(boolean dbOk, String dbStatus, String modelStatus) {
  }

  public HealthCheck check() {
    String dbStatus = probeDb();
    String modelStatus = probeModel();
    return new HealthCheck("ok".equals(dbStatus), dbStatus, modelStatus);
  }

  /**
   * {@link Connection#isValid(int)} probe with a 2-second timeout.
   */
  private String probeDb() {
    try (Connection conn = dataSource.getConnection()) {
      return conn.isValid(2) ? "ok" : "down";
    } catch (Exception e) {
      return "down";
    }
  }

  /**
   * Delegates to {@link ModelGateway#isModelProviderReachable()} — no model calls.
   */
  private String probeModel() {
    try {
      return modelGateway.isModelProviderReachable() ? "reachable" : "unreachable";
    } catch (Exception e) {
      return "unknown";
    }
  }
}
