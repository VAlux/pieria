package dev.alvo.pieria.client;

import dev.alvo.pieria.api.request.OnboardPlanRequest;
import dev.alvo.pieria.api.response.TaskSubmitResponse;

import java.time.Duration;

public final class OnboardingClient {
  private final DaemonTransport transport;

  OnboardingClient(DaemonTransport transport) {
    this.transport = transport;
  }

  public OnboardingClient(String baseUrl) {
    this(new DaemonTransport(baseUrl));
  }

  public OnboardingClient(String baseUrl, ClientIdentity identity) {
    this(new DaemonTransport(baseUrl, identity));
  }

  public TaskSubmitResponse submit(String profile, OnboardPlanRequest request, String label) {
    String path = DaemonTransport.withQuery(
      "/v1/profiles/" + DaemonTransport.segment(profile) + "/onboard/async", "label", label);
    return transport.parse(transport.post(path, request, Duration.ofSeconds(10)), TaskSubmitResponse.class);
  }
}
