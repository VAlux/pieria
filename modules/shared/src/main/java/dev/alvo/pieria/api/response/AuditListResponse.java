package dev.alvo.pieria.api.response;

import java.util.List;

/** One keyset-paginated page of profile audit events. */
public record AuditListResponse(List<AuditEventSummary> events, String nextCursor) {
}
