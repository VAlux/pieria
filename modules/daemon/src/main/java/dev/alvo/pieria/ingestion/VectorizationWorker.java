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

/**
 * Drains the vectorization outbox: embeds the {@code embed_text} of every pending memory in one
 * batched model call and persists the vectors, deleting each outbox row only
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

  public VectorizationWorker(MemoryStore store, ModelGateway modelGateway, PieriaProperties properties) {
    this.store = store;
    this.modelGateway = modelGateway;

    Ingestion ingestion = properties.ingestion();
    this.batchSize = Math.max(1, ingestion.outboxBatchSize());
    this.maxAttempts = Math.max(1, ingestion.outboxMaxAttempts());
  }

  /**
   * Drain and process a single batch. Returns the number of memories successfully vectorized.
   * The whole batch is embedded in one model call; the method returns once the batch settles.
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

    // Resolve each entry to either a terminal outcome or the text to embed. Model-free, and cheap
    // enough (point reads) not to need the virtual threads that used to overlap the embed calls —
    // there is only one of those left to overlap.
    List<PreparedWrite> prepared = new ArrayList<>(batch.size());
    List<Pending> pending = new ArrayList<>(batch.size());
    for (OutboxEntry entry : batch) {
      PreparedWrite terminal = resolve(entry, pending);
      if (terminal != null) {
        prepared.add(terminal);
      }
    }

    prepared.addAll(embedPending(pending));

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
   * Read one outbox entry (no DB writes, no model call). Returns a terminal {@link PreparedWrite}
   * for an entry that needs no embedding, or {@code null} after appending the entry's text to
   * {@code pending} for the batch embed.
   */
  private PreparedWrite resolve(OutboxEntry entry, List<Pending> pending) {
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
    log.debug("vectorization embedding queued memoryId={} type={} attempt={} textChars={}",
      memoryId, memory.type(), entry.attempts() + 1, text == null ? 0 : text.length());
    pending.add(new Pending(entry, text));
    return null;
  }

  /**
   * Embed the whole batch in one round trip, falling back to one call per entry when that fails.
   * The fallback is what preserves per-entry fault isolation: without it a single unembeddable text
   * would fail every other entry in the batch alongside it, and repeated drains would march them all
   * to {@code maxAttempts} and abandon healthy memories. Never throws.
   */
  private List<PreparedWrite> embedPending(List<Pending> pending) {
    if (pending.isEmpty()) {
      return List.of();
    }
    try {
      List<float[]> vectors = modelGateway.embedAll(pending.stream().map(Pending::text).toList());
      if (vectors.size() != pending.size()) {
        throw new IllegalStateException("embedAll returned " + vectors.size()
          + " vectors for " + pending.size() + " texts");
      }
      List<PreparedWrite> writes = new ArrayList<>(pending.size());
      for (int i = 0; i < pending.size(); i++) {
        writes.add(new PreparedWrite(VectorizationOutcome.SUCCEEDED,
          pending.get(i).entry().memoryId(), vectors.get(i), null));
      }
      return writes;
    } catch (RuntimeException e) {
      log.warn("batch embedding of {} entries failed ({}); falling back to one call per entry",
        pending.size(), e.getMessage());
      return pending.stream().map(this::embedOne).toList();
    }
  }

  /**
   * Embed one pending entry. Never throws — a failure is captured as a
   * {@link VectorizationOutcome#FAILED} {@link PreparedWrite} so the serial write phase records it
   * against that entry alone.
   */
  private PreparedWrite embedOne(Pending pending) {
    String memoryId = pending.entry().memoryId();
    try {
      return new PreparedWrite(VectorizationOutcome.SUCCEEDED, memoryId,
        modelGateway.embed(pending.text()), null);
    } catch (RuntimeException e) {
      log.warn("embedding failed for memory {} (attempt {}): {}",
        memoryId, pending.entry().attempts() + 1, e.getMessage());
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

  private enum VectorizationOutcome {
    SUCCEEDED,
    FAILED,
    ABANDONED,
    ORPHANED
  }

  /**
   * An outbox entry that survived resolution, paired with the text to embed for it.
   */
  private record Pending(OutboxEntry entry, String text) {
  }

  /**
   * One entry's embed result, awaiting its (serial) database write.
   */
  private record PreparedWrite(VectorizationOutcome outcome, String memoryId, float[] embedding,
                               String failureMessage) {
  }
}
