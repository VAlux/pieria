package dev.alvo.pieria.ingestion;

import dev.alvo.pieria.tools.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically triggers {@link VectorizationWorker#drainOnce()}. Split from the worker
 * so the draining logic stays unit-testable without a scheduler. Disabled in tests via
 * {@code pieria.ingestion.vectorization-scheduler-enabled=false}; the run interval is configurable
 * through {@code pieria.ingestion.vectorization-interval-ms}.
 */
@Component
@ConditionalOnProperty(
  prefix = "pieria.ingestion",
  name = "vectorization-scheduler-enabled",
  havingValue = "true",
  matchIfMissing = true)
public class VectorizationScheduler {

  private static final Logger log = LoggerFactory.getLogger(VectorizationScheduler.class);

  private final VectorizationWorker worker;

  public VectorizationScheduler(VectorizationWorker worker) {
    this.worker = worker;
  }

  @Scheduled(fixedDelayString = "${pieria.ingestion.vectorization-interval-ms:5000}")
  public void drain() {
    long start = System.nanoTime();
    log.trace("vectorization drain tick start");
    try {
      int processed = worker.drainOnce();
      if (processed > 0) {
        log.debug("vectorization drain tick completed processed={} totalMs={}",
          processed, Timed.elapsedMillis(start));
      } else {
        log.trace("vectorization drain tick completed processed=0 totalMs={}", Timed.elapsedMillis(start));
      }
    } catch (RuntimeException e) {
      // Never let a batch failure kill the scheduler thread; the next tick retries pending rows.
      log.warn("vectorization drain tick failed", e);
    }
  }
}
