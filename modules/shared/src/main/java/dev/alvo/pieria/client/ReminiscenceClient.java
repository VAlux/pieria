package dev.alvo.pieria.client;

import dev.alvo.pieria.api.response.OrphanCountResponse;
import dev.alvo.pieria.api.response.TaskSubmitResponse;

import java.time.Duration;
import java.util.Map;

/**
 * Client for orphan adoption ("reminiscence"): submit the async run and read the cheap dry-run count.
 */
public final class ReminiscenceClient {
  private final DaemonTransport transport;

  ReminiscenceClient(DaemonTransport transport) {
    this.transport = transport;
  }

  public ReminiscenceClient(String baseUrl) {
    this(new DaemonTransport(baseUrl));
  }

  /** Submit an orphan-adoption run; returns the task id to poll via {@link TaskClient}. */
  public TaskSubmitResponse submit(String profile, String label) {
    String path = DaemonTransport.withQuery(
      "/v1/profiles/" + DaemonTransport.segment(profile) + "/reminisce/async", "label", label);
    // No request body; send an empty JSON object (the endpoint takes no @RequestBody).
    return transport.parse(transport.post(path, Map.of(), Duration.ofSeconds(10)), TaskSubmitResponse.class);
  }

  /** How many orphans a run would adopt, without touching the model. */
  public long orphanCount(String profile) {
    String path = "/v1/profiles/" + DaemonTransport.segment(profile) + "/reminisce/orphans";
    return transport.parse(transport.get(path, Duration.ofSeconds(10)), OrphanCountResponse.class).orphans();
  }
}
