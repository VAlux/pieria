package dev.alvo.pieria.ingestion;


import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.PieriaProperties.Ingestion;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.ingestion.model.OutboxEntry;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.tools.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;

/**
 * Drains the vectorization outbox: for each pending memory, embeds its
 * {@code embed_text} on a virtual thread and persists the vector, deleting the outbox row only
 * after the embedding write commits ({@link MemoryStore#completeVectorization}). Failures increment
 * the attempt counter; entries past {@code outboxMaxAttempts} are abandoned (outbox row dropped) to
 * avoid a poison-message loop.
 *
 * <p>This component holds only the draining logic; the periodic trigger lives in
 * {@link VectorizationScheduler} so the worker is trivially unit-testable via {@link #drainOnce()}.
 */
@Component
public class VectorizationWorker {

  private static final Logger log = LoggerFactory.getLogger(VectorizationWorker.class);

  private final MemoryStore store;
  private final ModelGateway modelGateway;
  private final int batchSize;
  private final int maxAttempts;

  private enum VectorizationOutcome {
    SUCCEEDED,
    FAILED,
    ABANDONED,
    ORPHANED
  }

  public VectorizationWorker(MemoryStore store, ModelGateway modelGateway, PieriaProperties properties) {
    this.store = store;
    this.modelGateway = modelGateway;
    Ingestion ingestion = properties.ingestion();
    this.batchSize = Math.max(1, ingestion.outboxBatchSize());
    this.maxAttempts = Math.max(1, ingestion.outboxMaxAttempts());
  }

  /**
   * Drain and process a single batch. Returns the number of memories successfully vectorized.
   * Blocking embedding calls run on virtual threads; the method returns once the batch settles.
   */
  public int drainOnce() {
    long start = System.nanoTime();
    List<OutboxEntry> batch = store.drainOutbox(batchSize);
    if (batch.isEmpty()) {
      log.trace("vectorization batch empty batchSize={}", batchSize);
      return 0;
    }
    log.debug("vectorization batch start entries={} batchSize={} maxAttempts={}",
      batch.size(), batchSize, maxAttempts);

    try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<VectorizationOutcome>> futures = new ArrayList<>(batch.size());
      for (OutboxEntry entry : batch) {
        futures.add(exec.submit(() -> process(entry)));
      }
      int succeeded = 0;
      int failed = 0;
      int abandoned = 0;
      int orphaned = 0;
      for (Future<VectorizationOutcome> f : futures) {
        try {
          switch (f.get()) {
            case SUCCEEDED -> succeeded++;
            case FAILED -> failed++;
            case ABANDONED -> abandoned++;
            case ORPHANED -> orphaned++;
          }
        } catch (ExecutionException e) {
          failed++;
          log.warn("vectorization task failed unexpectedly", e.getCause());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      log.info("vectorization batch entries={} succeeded={} failed={} abandoned={} orphaned={} totalMs={}",
        batch.size(), succeeded, failed, abandoned, orphaned, Timed.elapsedMillis(start));
      return succeeded;
    }
  }

  private VectorizationOutcome process(OutboxEntry entry) {
    if (entry.attempts() >= maxAttempts) {
      // Poison message: drop the outbox row so it stops being drained. The memory stays
      // un-embedded; a later re-ingest can re-enqueue it.
      log.error("abandoning vectorization for memory {} after {} attempts", entry.memoryId(), entry.attempts());
      store.deleteOutboxRow(entry.memoryId());
      return VectorizationOutcome.ABANDONED;
    }

    Memory memory = store.findMemoryById(entry.memoryId()).orElse(null);
    if (memory == null) {
      // The memory was superseded/removed after enqueue; nothing to embed.
      log.debug("outbox memory {} no longer present; dropping", entry.memoryId());
      store.deleteOutboxRow(entry.memoryId());
      return VectorizationOutcome.ORPHANED;
    }

    String text = memory.embedText() != null && !memory.embedText().isBlank()
      ? memory.embedText()
      : memory.content();
    log.debug("vectorization embedding start memoryId={} type={} attempt={} textChars={}",
      entry.memoryId(), memory.type(), entry.attempts() + 1, text == null ? 0 : text.length());
    try {
      float[] embedding = modelGateway.embed(text);
      store.completeVectorization(entry.memoryId(), embedding);
      log.debug("vectorization embedding stored memoryId={} dimensions={}",
        entry.memoryId(), embedding == null ? 0 : embedding.length);
      return VectorizationOutcome.SUCCEEDED;
    } catch (RuntimeException e) {
      log.warn("embedding failed for memory {} (attempt {}): {}",
        entry.memoryId(), entry.attempts() + 1, e.getMessage());
      store.recordOutboxFailure(entry.memoryId(), e.getMessage());
      return VectorizationOutcome.FAILED;
    }
  }
}
