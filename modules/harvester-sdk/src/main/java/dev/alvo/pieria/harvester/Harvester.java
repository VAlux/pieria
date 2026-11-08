package dev.alvo.pieria.harvester;

import dev.alvo.pieria.harvester.protocol.HarvestRequest;

/** A knowledge source. Host one with {@link HarvesterRunner#main}. */
public interface Harvester {

  /** Validates the config and credentials. Returning normally means they are good. */
  void check(HarvestRequest request) throws HarvesterException;

  /**
   * Reports the source's items to {@code sink}. A run resumes from {@code request.cursor()} and honours
   * {@code request.limits()}; a run that was resumed or stopped by a limit must not emit a sweep.
   */
  void harvest(HarvestRequest request, RecordSink sink) throws HarvesterException;
}
