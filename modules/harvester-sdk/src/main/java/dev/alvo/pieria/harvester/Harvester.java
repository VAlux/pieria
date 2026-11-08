package dev.alvo.pieria.harvester;

import dev.alvo.pieria.harvester.protocol.HarvestRequest;

/** A knowledge source. Host one with {@link HarvesterRunner#main}. */
public interface Harvester {

  /**
   * Validates the config and credentials. Returning normally means they are good. {@code sink} accepts
   * only {@code SecretUpdate}, for credentials that rotate when they are used.
   */
  void check(HarvestRequest request, RecordSink sink) throws HarvesterException;

  /**
   * Reports the source's items to {@code sink}. A run continues from {@code request.cursor()} and
   * honours {@code request.limits()}. Emit a sweep only from a run that reported every live item, as
   * an item or in {@code seen}, whatever cursor it started from.
   */
  void harvest(HarvestRequest request, RecordSink sink) throws HarvesterException;
}
