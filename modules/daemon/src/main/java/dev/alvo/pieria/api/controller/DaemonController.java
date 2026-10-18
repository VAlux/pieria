package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.DaemonRestartRequest;
import dev.alvo.pieria.api.response.DaemonRestartResponse;
import dev.alvo.pieria.daemon.DaemonRestartService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Process control the console needs: restarting after a configuration change. */
@RestController
public class DaemonController {

  private final DaemonRestartService restarts;

  public DaemonController(DaemonRestartService restarts) {
    this.restarts = restarts;
  }

  /** 202 and exit shortly after; 409 when no service manager would bring the daemon back, or tasks run. */
  @PostMapping("/v1/daemon/restart")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public DaemonRestartResponse restart(@RequestBody DaemonRestartRequest request) {
    DaemonRestartService.Scheduled scheduled = restarts.requestRestart(Boolean.TRUE.equals(request.force()));
    return new DaemonRestartResponse(scheduled.mechanism(), scheduled.expectedDowntimeMs());
  }
}
