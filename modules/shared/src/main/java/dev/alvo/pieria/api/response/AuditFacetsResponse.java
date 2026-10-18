package dev.alvo.pieria.api.response;

import java.util.List;
import java.util.Map;

/**
 * GET /v1/audit/facets: distinct values with counts under the current filters, keyed by dimension
 * ({@code operation}, {@code client}, {@code harness}, {@code channel}, {@code outcome}, {@code profile}),
 * most frequent first. Feeds the activity view's filter menus.
 */
public record AuditFacetsResponse(Map<String, List<Value>> facets) {

  public record Value(String value, long count) {
  }
}
