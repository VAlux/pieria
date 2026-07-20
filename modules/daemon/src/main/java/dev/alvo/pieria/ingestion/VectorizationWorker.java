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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    // Embed in parallel on virtual threads (the slow model calls), producing the work to
    // write but performing NO database writes.
    List<PreparedWrite> prepared = new ArrayList<>(batch.size());
    try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<PreparedWrite>> futures = new ArrayList<>(batch.size());
      for (OutboxEntry entry : batch) {
        futures.add(exec.submit(() -> prepare(entry)));
      }
      for (Future<PreparedWrite> f : futures) {
        try {
          prepared.add(f.get());
        } catch (ExecutionException e) {
          // prepare() never throws, but guard anyway: leave the outbox row for the next drain.
          log.warn("vectorization prepare failed unexpectedly", e.getCause());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    // Apply the database writes serially on this thread. SQLite is single-writer, so
    // concurrent UPDATEs only collide (SQLITE_BUSY); serializing them removes vectorization-vs-
    // vectorization contention, leaving only single-writer-vs-ingestion, which busy_timeout covers.
    int succeeded = 0;
    int failed = 0;
    int abandoned = 0;
    int orphaned = 0;
    for (PreparedWrite p : prepared) {
      switch (applyWrite(p)) {
        case SUCCEEDED -> succeeded++;
        case FAILED -> failed++;
        case ABANDONED -> abandoned++;
        case ORPHANED -> orphaned++;
      }
    }
    log.info("vectorization batch entries={} succeeded={} failed={} abandoned={} orphaned={} totalMs={}",
      batch.size(), succeeded, failed, abandoned, orphaned, Timed.elapsedMillis(start));
    return succeeded;
  }

  /**
   * Read + embed one outbox entry (no DB writes). Never throws — an embedding failure is captured as a
   * {@link VectorizationOutcome#FAILED} {@link PreparedWrite} so the serial write phase records it.
   */
  private PreparedWrite prepare(OutboxEntry entry) {
    String memoryId = entry.memoryId();
    if (entry.attempts() >= maxAttempts) {
      // Poison message: drop the outbox row so it stops being drained (the memory stays un-embedded;
      // a later re-ingest can re-enqueue it).
      log.error("abandoning vectorization for memory {} after {} attempts", memoryId, entry.attempts());
      return new PreparedWrite(VectorizationOutcome.ABANDONED, memoryId, null, null);
    }
    Memory memory = store.findMemoryById(memoryId).orElse(null);
    if (memory == null) {
      // The memory was superseded/removed after enqueue; nothing to embed.
      log.debug("outbox memory {} no longer present; dropping", memoryId);
      return new PreparedWrite(VectorizationOutcome.ORPHANED, memoryId, null, null);
    }
    String text = memory.embedText() != null && !memory.embedText().isBlank()
      ? memory.embedText()
      : memory.content();
    log.debug("vectorization embedding start memoryId={} type={} attempt={} textChars={}",
      memoryId, memory.type(), entry.attempts() + 1, text == null ? 0 : text.length());
    try {
      float[] embedding = modelGateway.embed(text);
      return new PreparedWrite(VectorizationOutcome.SUCCEEDED, memoryId, embedding, null);
    } catch (RuntimeException e) {
      log.warn("embedding failed for memory {} (attempt {}): {}",
        memoryId, entry.attempts() + 1, e.getMessage());
      return new PreparedWrite(VectorizationOutcome.FAILED, memoryId, null, e.getMessage());
    }
  }

  /**
   * Apply one prepared outcome's database write, serially (single writer) from {@link #drainOnce}. A
   * write that still fails (e.g. busy_timeout exhausted while ingestion holds the lock) is recorded as
   * a failure so the entry retries on the next drain rather than aborting the batch.
   */
  private VectorizationOutcome applyWrite(PreparedWrite prepared) {
    String memoryId = prepared.memoryId();
    try {
      switch (prepared.outcome()) {
        case SUCCEEDED -> {
          store.completeVectorization(memoryId, prepared.embedding());
          log.debug("vectorization embedding stored memoryId={} dimensions={}",
            memoryId, prepared.embedding() == null ? 0 : prepared.embedding().length);
        }
        case FAILED -> store.recordOutboxFailure(memoryId, prepared.failureMessage());
        case ABANDONED, ORPHANED -> store.deleteOutboxRow(memoryId);
      }
      return prepared.outcome();
    } catch (RuntimeException e) {
      log.warn("vectorization write failed for memory {} ({}); recording for retry", memoryId, e.getMessage());
      try {
        store.recordOutboxFailure(memoryId, e.getMessage());
      } catch (RuntimeException ignored) {
        // The outbox row simply remains for the next drain.
      }
      return VectorizationOutcome.FAILED;
    }
  }

  /**
   * One entry's embed result, awaiting its (serial) database write.
   */
  private record PreparedWrite(VectorizationOutcome outcome, String memoryId, float[] embedding,
                               String failureMessage) {
  }
}
