package dev.alvo.pieria.harvesters.atlassian;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;

import java.util.List;

/**
 * Offset paging that notices when rows shift under it. Each page starts {@link #OVERLAP} rows before the
 * previous one ended and must contain that page's last row. A deletion, or a row leaving the query,
 * shifts later rows left; up to {@code OVERLAP} shifts are absorbed, and more fail the run rather than
 * silently skip a row, which a sweep would then delete.
 */
public final class OffsetPaging {

  public static final int OVERLAP = 10;

  /** Where a page starts, and the last row of the page before it (null on the first page). */
  public record Position(int start, String lastId) {
  }

  private OffsetPaging() {
  }

  public static Position read(String token) {
    if (token == null) {
      return new Position(0, null);
    }
    int colon = token.indexOf(':');
    return new Position(Integer.parseInt(token.substring(0, colon)), token.substring(colon + 1));
  }

  /** The token for the page after one that started at {@code start} and held {@code ids}. */
  public static String next(int start, List<String> ids) {
    return Math.max(start + 1, start + ids.size() - OVERLAP) + ":" + ids.getLast();
  }

  public static void requireContinuity(Position position, List<String> ids, String label) throws HarvesterException {
    if (position.lastId() != null && !ids.contains(position.lastId())) {
      throw new HarvesterException(ErrorClass.TRANSIENT, label + " results shifted while paging; the next run will try again");
    }
  }
}
