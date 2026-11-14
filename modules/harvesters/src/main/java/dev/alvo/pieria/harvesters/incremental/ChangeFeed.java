package dev.alvo.pieria.harvesters.incremental;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;

import java.time.Duration;
import java.time.Instant;

/**
 * A source that can list what changed recently and every item it holds. Pages may overlap: the
 * engine reports each item state once per run, so a feed can step back a few rows to absorb rows
 * that shifted while it paged.
 */
public interface ChangeFeed<T> {

  /** Checks the credentials, and returns the source's current time. */
  Instant begin() throws HarvesterException;

  /**
   * Items changed within {@code lookback} of the source's now, oldest change first.
   *
   * @param lookback null for every item
   * @param token    null for the first page, else a previous page's {@code next}
   */
  Page<Change<T>> changes(Duration lookback, String token) throws HarvesterException;

  /** The ids of every live item, in an order that editing an item does not change. */
  Page<String> liveIds(String token) throws HarvesterException;

  /** The record to report, or null when the item has gone since it was listed. */
  HarvestRecord.Item load(Change<T> change) throws HarvesterException;
}
