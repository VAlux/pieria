package dev.alvo.pieria.client;

import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.api.response.MemoryListResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.ProfileListResponse;
import dev.alvo.pieria.api.response.ProfileStatsResponse;
import dev.alvo.pieria.api.response.ProfileSummary;
import dev.alvo.pieria.api.response.RecallResponse;

import java.time.Duration;

public final class ProfileClient {
  private final DaemonTransport transport;

  ProfileClient(DaemonTransport transport) {
    this.transport = transport;
  }

  public ProfileClient(String baseUrl) {
    this(new DaemonTransport(baseUrl));
  }

  private String profile(String name) {
    return "/v1/profiles/" + DaemonTransport.segment(name);
  }

  public ProfileListResponse list() {
    return transport.parse(transport.get("/v1/profiles", Duration.ofSeconds(15)), ProfileListResponse.class);
  }

  public ProfileSummary create(String name) {
    return transport.parse(transport.putEmpty(profile(name), Duration.ofSeconds(10)), ProfileSummary.class);
  }

  public void delete(String name) {
    transport.delete(profile(name), Duration.ofSeconds(30));
  }

  public ProfileStatsResponse stats(String name) {
    return transport.parse(transport.get(profile(name) + "/stats", Duration.ofSeconds(15)), ProfileStatsResponse.class);
  }

  public MemoryListResponse memories(String name, String type, String session) {
    String path = DaemonTransport.withQuery(profile(name) + "/memories", "type", type, "session", session);
    return transport.parse(transport.get(path, Duration.ofSeconds(15)), MemoryListResponse.class);
  }

  public RecallResponse recall(String name, RecallRequest request) {
    return transport.parse(
      transport.post(profile(name) + "/recall", request, Duration.ofSeconds(60)), RecallResponse.class);
  }

  public MemoryResponse remember(String name, RememberRequest request) {
    return transport.parse(
      transport.post(profile(name) + "/memories", request, Duration.ofSeconds(60)), MemoryResponse.class);
  }

  public void forget(String name, String id) {
    transport.delete(profile(name) + "/memories/" + DaemonTransport.segment(id), Duration.ofSeconds(10));
  }

  public String export(String name) {
    return transport.get(profile(name) + "/export", Duration.ofSeconds(30));
  }

  public String toJson(Object value) {
    return transport.toJson(value);
  }
}
