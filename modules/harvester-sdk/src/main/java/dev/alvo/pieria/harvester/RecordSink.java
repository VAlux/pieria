package dev.alvo.pieria.harvester;

import dev.alvo.pieria.harvester.protocol.HarvestRecord;

/** Where a harvester sends its records. Safe to call from several threads. */
public interface RecordSink {

  /**
   * Writes one record and flushes it.
   *
   * @throws SinkClosedException when the reader has gone away; the harvester should stop
   */
  void emit(HarvestRecord record);
}
