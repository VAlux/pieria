package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.AuditListRequest;
import dev.alvo.pieria.api.response.AuditEventDetail;
import dev.alvo.pieria.api.response.AuditFacetsResponse;
import dev.alvo.pieria.api.response.AuditListResponse;
import dev.alvo.pieria.audit.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Daemon-wide activity: the audit history of every profile in one feed, optionally narrowed to one
 * with {@code profile}, plus the filter facets the console's activity view offers. Lives outside
 * {@code /v1/profiles/} so reading the log is not itself recorded in it.
 */
@RestController
@RequestMapping("/v1/audit")
public class ActivityController {

  private final AuditService audit;

  public ActivityController(AuditService audit) {
    this.audit = audit;
  }

  @GetMapping
  public AuditListResponse list(
    @RequestParam(required = false) String profile,
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
    return audit.search(blankToNull(profile), new AuditListRequest(search, operation, client, harness, channel,
      outcome, status, session, taskId, requestId, from, to, truncated, limit, cursor));
  }

  @GetMapping("/facets")
  public AuditFacetsResponse facets(
    @RequestParam(required = false) String profile,
    @RequestParam(name = "q", required = false) String search,
    @RequestParam(required = false) String operation,
    @RequestParam(required = false) String client,
    @RequestParam(required = false) String harness,
    @RequestParam(required = false) String channel,
    @RequestParam(required = false) String outcome,
    @RequestParam(required = false) String session,
    @RequestParam(required = false) String from,
    @RequestParam(required = false) String to) {
    return audit.facets(blankToNull(profile), new AuditListRequest(search, operation, client, harness, channel,
      outcome, null, session, null, null, from, to, null, null, null));
  }

  @GetMapping("/{id}")
  public AuditEventDetail detail(@PathVariable String id) {
    return audit.detail(null, id);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
