package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.StatusResponse;
import dev.alvo.pieria.api.response.StatusResponse.Setup;
import dev.alvo.pieria.status.StatusService;
import dev.alvo.pieria.status.StatusService.StatusView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local status endpoint for first-run and operational checks. It deliberately omits provider URLs,
 * secrets, and row-level memory data.
 */
@RestController
@RequestMapping("/pieria-status")
public class StatusController {

  private final StatusService statusService;

  public StatusController(StatusService statusService) {
    this.statusService = statusService;
  }

  @GetMapping
  public StatusResponse status() {
    StatusView status = statusService.status();

    return new StatusResponse(
      status.status(),
      status.databaseFile(),
      status.backend(),
      status.vectorSearch(),
      status.provider(),
      status.extractionModel(),
      status.synthesisModel(),
      status.embedding(),
      status.outboxDepth(),
      new Setup(
        status.setup().enabled(),
        status.setup().directoriesReady(),
        status.setup().databaseParentReady(),
        status.setup().modelStatus(),
        status.setup().modelPullPolicy(),
        status.setup().paths().configDir().toString(),
        status.setup().paths().logsDir().toString(),
        status.setup().paths().runtimeDir().toString()));
  }
}
