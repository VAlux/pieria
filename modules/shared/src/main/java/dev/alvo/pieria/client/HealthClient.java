package dev.alvo.pieria.client;

import dev.alvo.pieria.api.response.HealthResponse;
import dev.alvo.pieria.api.response.StatusResponse;
import dev.alvo.pieria.client.exception.DaemonClientException;
import dev.alvo.pieria.client.exception.DaemonHttpException;
import dev.alvo.pieria.client.exception.DaemonInterruptedException;
import dev.alvo.pieria.client.exception.DaemonUnavailableException;

import java.time.Duration;

public final class HealthClient {
  private static final Duration TIMEOUT = Duration.ofSeconds(3);
  private static final String PIERIA_HEALTH_PATH = "/pieria-health";

  private final DaemonTransport transport;

  public record HealthStatusSnapshot(int statusCode, HealthResponse health, StatusResponse status) {
    public boolean healthy() {
      return statusCode >= 200 && statusCode < 300;
    }
  }

  HealthClient(DaemonTransport transport) {
    this.transport = transport;
  }

  public HealthClient(String baseUrl) {
    this(new DaemonTransport(baseUrl));
  }

  public HealthClient(String baseUrl, ClientIdentity identity) {
    this(new DaemonTransport(baseUrl, identity));
  }

  public boolean reachable() {
    try {
      transport.probe(PIERIA_HEALTH_PATH, TIMEOUT);
      return true;
    } catch (DaemonUnavailableException | DaemonInterruptedException e) {
      return false;
    }
  }

  public HealthStatusSnapshot snapshot() {
    int status = transport.probe(PIERIA_HEALTH_PATH, TIMEOUT);
    String body;
    try {
      body = transport.get(PIERIA_HEALTH_PATH, TIMEOUT);
    } catch (DaemonHttpException e) {
      body = e.body();
    }
    HealthResponse health = body.isBlank() ? null : transport.parse(body, HealthResponse.class);
    StatusResponse detail = null;

    try {
      detail = transport.parse(transport.get("/pieria-status", TIMEOUT), StatusResponse.class);
    } catch (DaemonClientException ignored) {
    }

    return new HealthStatusSnapshot(status, health, detail);
  }

  public boolean awaitReachable(Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();

    while (System.nanoTime() < deadline) {
      if (reachable()) {
        return true;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new DaemonInterruptedException(e);
      }
    }

    return false;
  }
}
