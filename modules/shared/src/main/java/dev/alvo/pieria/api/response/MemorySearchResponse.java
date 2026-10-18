package dev.alvo.pieria.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One page of GET /v1/profiles/{name}/memories/search.
 *
 * @param items      memories and collapsed tool-call groups in list order
 * @param nextCursor opaque position of the next page; null on the last page
 * @param typeCounts matches per type under the text and session filters, ignoring the type filter
 * @param total      matches under every filter
 */
public record MemorySearchResponse(List<Item> items, String nextCursor, Map<String, Long> typeCounts, long total) {

  /** A memory ({@code kind = "memory"}) or a session's trace outcomes ({@code kind = "tool-calls"}). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Item(String kind, MemoryResponse memory, ToolCallGroup group) {
  }

  /** {@code count} is outcomes currently visible: one per distinct command, since reruns supersede. */
  public record ToolCallGroup(String sessionId, long count, List<String> tools, Instant firstAt, Instant lastAt) {
  }
}
