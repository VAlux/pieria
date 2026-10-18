package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.ProviderTestRequest;
import dev.alvo.pieria.api.response.ProviderTestResponse;
import dev.alvo.pieria.model.ProviderProbeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Tries provider settings from the console's global configuration page before they are saved. */
@RestController
public class ProviderTestController {

  private final ProviderProbeService probes;

  public ProviderTestController(ProviderProbeService probes) {
    this.probes = probes;
  }

  @PostMapping("/v1/config/provider/test")
  public ProviderTestResponse test(@RequestBody ProviderTestRequest request) {
    var results = probes.probe(new ProviderProbeService.Probe(request.type(), request.baseUrl(), request.apiKey(),
      request.apiVersion(), request.extractionModel(), request.synthesisModel(), request.embeddingModel()));
    return new ProviderTestResponse(results.stream()
      .map(r -> new ProviderTestResponse.Tier(r.tier(), r.model(), r.reachable(), r.latencyMs(), r.errorKind(), r.error(),
        r.embeddingDimensions(), r.dimensionMatches()))
      .toList());
  }
}
