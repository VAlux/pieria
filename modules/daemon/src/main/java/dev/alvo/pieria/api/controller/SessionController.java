package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.SessionListResponse;
import dev.alvo.pieria.profile.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sessions of a profile, for the console's session filter and the add-memory session picker. */
@RestController
public class SessionController {

  private final SessionService sessions;

  public SessionController(SessionService sessions) {
    this.sessions = sessions;
  }

  @GetMapping("/v1/profiles/{name}/sessions")
  public SessionListResponse list(@PathVariable String name,
                                  @RequestParam(name = "q", required = false) String q,
                                  @RequestParam(name = "limit", defaultValue = "100") int limit) {
    return new SessionListResponse(sessions.list(name, q, limit).stream()
      .map(s -> new SessionListResponse.Session(s.sessionId(), s.firstSeenAt(), s.lastSeenAt(), s.memoryCount(),
        s.messageCount(), s.harness()))
      .toList());
  }
}
