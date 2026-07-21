package dev.alvo.pieria.client;

import dev.alvo.pieria.api.response.TaskListResponse;
import dev.alvo.pieria.api.response.TaskStatusResponse;

import java.time.Duration;

public final class TaskClient {
  private final DaemonTransport transport;

  TaskClient(DaemonTransport transport) {
    this.transport = transport;
  }

  public TaskClient(String baseUrl) {
    this(new DaemonTransport(baseUrl));
  }

  public TaskClient(String baseUrl, ClientIdentity identity) {
    this(new DaemonTransport(baseUrl, identity));
  }

  public TaskListResponse list() {
    return transport.parse(transport.get("/v1/tasks", Duration.ofSeconds(15)), TaskListResponse.class);
  }

  public TaskStatusResponse status(String id) {
    return transport.parse(
      transport.get("/v1/tasks/" + DaemonTransport.segment(id), Duration.ofSeconds(10)),
      TaskStatusResponse.class);
  }

  public TaskStatusResponse cancel(String id) {
    return transport.parse(
      transport.delete("/v1/tasks/" + DaemonTransport.segment(id), Duration.ofSeconds(10)),
      TaskStatusResponse.class);
  }
}
