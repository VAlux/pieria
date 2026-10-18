package dev.alvo.pieria.domain.memory;

/**
 * One page request for the console's memory list.
 *
 * @param text              free text matched against content, topic key and session id; null for none
 * @param type              only this type; null for all
 * @param session           only this session; null for all
 * @param newestFirst       sort direction on store time
 * @param limit             page size
 * @param cursor            where the previous page ended; null for the first page
 * @param includeSuperseded also list memories that are no longer visible now
 * @param groupToolCalls    collapse trace outcome events into one row per session
 */
public record MemorySearchQuery(String text, MemoryType type, String session, boolean newestFirst, int limit,
                                Cursor cursor, boolean includeSuperseded, boolean groupToolCalls) {

  /** Position after the last row of a page: its raw stored timestamp and id. */
  public record Cursor(String sortAt, String id) {
  }
}
