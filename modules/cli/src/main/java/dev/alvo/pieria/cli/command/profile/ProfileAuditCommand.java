package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.request.AuditListRequest;
import dev.alvo.pieria.api.response.AuditEventDetail;
import dev.alvo.pieria.api.response.AuditEventSummary;
import dev.alvo.pieria.api.response.AuditListResponse;
import dev.alvo.pieria.client.ProfileClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Search and inspect a profile's durable API audit history. */
@Command(name = "audit", description = "Search a profile's API audit history.", mixinStandardHelpOptions = true)
public final class ProfileAuditCommand extends AbstractProfileCommand {
  @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.") String name;
  @Option(names = "--search", description = "Full-text search across retained audit content.") String search;
  @Option(names = "--operation", description = "Filter by operation, e.g. recall or memory.remember.") String operation;
  @Option(names = "--client", description = "Filter by client: gateway, cli, console, hook, api.") String clientName;
  @Option(names = "--harness", description = "Filter by harness, e.g. codex or claude-code.") String harness;
  @Option(names = "--channel", description = "Filter by invocation channel: mcp, hook, cli, console, http.") String channel;
  @Option(names = "--outcome", description = "Filter by outcome: success, failure, cancelled.") String outcome;
  @Option(names = "--status", description = "Filter by exact HTTP status.") Integer status;
  @Option(names = "--session", description = "Filter by session id.") String session;
  @Option(names = "--task-id", description = "Filter by task id.") String taskId;
  @Option(names = "--request-id", description = "Filter by request correlation id.") String requestId;
  @Option(names = "--from", description = "Earliest completion time (ISO-8601 instant).") String from;
  @Option(names = "--to", description = "Latest completion time (ISO-8601 instant).") String to;
  @Option(names = "--truncated", description = "Show only events with a truncated request or response.") Boolean truncated;
  @Option(names = "--limit", description = "Rows to return, 1-200 (default: 50).") Integer limit;
  @Option(names = "--cursor", description = "Opaque cursor printed by the previous page.") String cursor;
  @Option(names = "--id", description = "Fetch one complete audit event by id.") String id;
  @Option(names = "--json", description = "Print the daemon response as JSON.") boolean json;

  @Override
  protected int run(ProfileClient client) {
    if (id != null && !id.isBlank()) {
      AuditEventDetail detail = client.auditDetail(name, id);
      if (json) log.info("{}", client.toJson(detail)); else renderDetail(detail);
      return 0;
    }
    AuditListResponse page = client.audit(name, new AuditListRequest(search, operation, clientName,
      harness, channel, outcome, status, session, taskId, requestId, from, to, truncated, limit, cursor));
    if (json) {
      log.info("{}", client.toJson(page));
      return 0;
    }
    if (page.events() == null || page.events().isEmpty()) {
      log.info("No audit events.");
      return 0;
    }
    for (AuditEventSummary event : page.events()) {
      String caller = event.harness() == null ? event.client() : event.harness() + "/" + event.channel();
      String statusText = event.httpStatus() == null ? event.outcome() : event.httpStatus().toString();
      log.info("%s  %-24s %-20s %-9s %d ms".formatted(event.completedAt(), event.operation(), caller,
        statusText, event.durationMs()));
      if (event.responsePreview() != null && !event.responsePreview().isBlank()) {
        log.info("    {}", event.responsePreview());
      }
      log.info("    id={} request={}", event.id(), event.requestId());
    }
    if (page.nextCursor() != null) {
      log.info("");
      log.info("More results: --cursor {}", page.nextCursor());
    }
    return 0;
  }

  private void renderDetail(AuditEventDetail e) {
    log.info("{} [{}] {}", e.completedAt(), e.outcome(), e.operation());
    log.info("id={} request={} parent={} task={}", e.id(), e.requestId(), value(e.parentRequestId()), value(e.taskId()));
    log.info("caller={} harness={} channel={} version={}", e.client(), value(e.harness()), e.channel(), value(e.clientVersion()));
    log.info("http={} {} status={} duration={} ms", value(e.method()), value(e.path()), value(e.httpStatus()), e.durationMs());
    if (e.errorMessage() != null) log.info("error={} {}", value(e.errorKind()), e.errorMessage());
    log.info("");
    log.info("Request ({} bytes, sha256={}{}):", e.requestBytes(), e.requestSha256(), e.requestTruncated() ? ", truncated" : "");
    log.info("{}", e.requestBody() == null || e.requestBody().isBlank() ? "(empty)" : e.requestBody());
    log.info("");
    log.info("Response ({} bytes, sha256={}{}):", e.responseBytes(), e.responseSha256(), e.responseTruncated() ? ", truncated" : "");
    log.info("{}", e.responseBody() == null || e.responseBody().isBlank() ? "(empty)" : e.responseBody());
  }

  private static String value(Object value) {
    return value == null ? "—" : value.toString();
  }
}
