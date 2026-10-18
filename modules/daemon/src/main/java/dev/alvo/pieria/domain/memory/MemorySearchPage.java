package dev.alvo.pieria.domain.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One page of the console's memory list.
 *
 * @param entries    memories, or collapsed tool-call groups, in list order
 * @param typeCounts matches per type under the text and session filters, ignoring the type filter
 * @param total      matches under every filter, the type filter included
 * @param next       where the following page starts; null on the last page
 */
public record MemorySearchPage(List<Entry> entries, Map<String, Long> typeCounts, long total,
                               MemorySearchQuery.Cursor next) {

  /** Exactly one of {@code memory} and {@code group} is set. */
  public record Entry(Memory memory, ToolCallGroup group) {
  }

  /**
   * The trace outcome events of one session folded into a single row. {@code count} is the number
   * of outcomes currently visible, which is one per distinct command since reruns supersede.
   */
  public record ToolCallGroup(String sessionId, long count, List<String> tools, Instant firstAt, Instant lastAt) {
  }
}
