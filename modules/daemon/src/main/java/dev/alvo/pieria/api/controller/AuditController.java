package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.AuditListRequest;
import dev.alvo.pieria.api.response.AuditEventDetail;
import dev.alvo.pieria.api.response.AuditListResponse;
import dev.alvo.pieria.audit.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Search and inspect a profile's append-only audit history.
 */
@RestController
@RequestMapping("/v1/profiles/{name}/audit")
public class AuditController {
  private final AuditService audit;

  public AuditController(AuditService audit) {
    this.audit = audit;
  }

  @GetMapping
  public AuditListResponse list(
    @PathVariable String name,
    @RequestParam(name = "q", required = false) String search,
    @RequestParam(required = false) String operation,
    @RequestParam(required = false) String client,
    @RequestParam(required = false) String harness,
    @RequestParam(required = false) String channel,
    @RequestParam(required = false) String outcome,
    @RequestParam(required = false) Integer status,
    @RequestParam(required = false) String session,
    @RequestParam(required = false) String taskId,
    @RequestParam(required = false) String requestId,
    @RequestParam(required = false) String from,
    @RequestParam(required = false) String to,
    @RequestParam(required = false) Boolean truncated,
    @RequestParam(required = false) Integer limit,
    @RequestParam(required = false) String cursor) {
    return audit.search(name, new AuditListRequest(search, operation, client, harness, channel,
      outcome, status, session, taskId, requestId, from, to, truncated, limit, cursor));
  }

  @GetMapping("/{id}")
  public AuditEventDetail detail(@PathVariable String name, @PathVariable String id) {
    return audit.detail(name, id);
  }
}
