package dev.alvo.pieria.client;

import dev.alvo.pieria.api.request.SourceSpec;
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

  public TaskSubmitResponse submit(String profile, SourceSpec source) {
    return submit(profile, source, null);
  }

  public TaskSubmitResponse submit(String profile, SourceSpec source, String label) {
    String path = DaemonTransport.withQuery(
      "/v1/profiles/" + DaemonTransport.segment(profile) + "/onboard/async", "label", label);
    return transport.parse(transport.post(path, source, Duration.ofSeconds(10)), TaskSubmitResponse.class);
  }
}
