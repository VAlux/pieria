package dev.alvo.pieria.harvesters.incremental;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;

import java.time.Instant;
import java.util.List;

/**
 * A source that can list what changed recently and every item it holds. Pages may overlap: the
 * engine reports each item state once per run, so a feed can step back a few rows to absorb rows
 * that shifted while it paged.
 */
public interface ChangeFeed<T> {

  /** Checks the credentials, and returns the source's current time. */
  Instant begin() throws HarvesterException;

  /**
   * Items changed at or after {@code from}, oldest change first. Every page of one listing gets the
   * same {@code from}, so a source that measures it relative to its own now must re-measure it for
   * each page. Entries need only what {@link #load} needs.
   *
   * @param from  null for every item
   * @param token null for the first page, else a previous page's {@code next}
   */
  Page<Change<T>> changes(Instant from, String token) throws HarvesterException;

  /** The ids of every live item, in an order that editing an item does not change. */
  Page<String> liveIds(String token) throws HarvesterException;

  /** The records for these changes. An item that has gone since it was listed is left out. */
  List<HarvestRecord.Item> load(List<Change<T>> changes) throws HarvesterException;

  /**
   * Whether a listed change that {@link #load} left out means the item is gone, so the engine reports
   * a tombstone for it. For a feed that never sweeps, this is how deletions are found.
   */
  default boolean tombstonesGone() {
    return false;
  }

  /**
   * Whether {@link #changes} pages come oldest change first. A feed that walks one container after
   * another, such as one chat after the next, can't promise that, so the engine lists its whole window
   * before reporting any of it rather than stopping at {@link IncrementalHarvest#MAX_PENDING}.
   */
  default boolean listsInOrder() {
    return true;
  }
}
