package dev.alvo.pieria.task;

import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.model.ModelFailures;
import dev.alvo.pieria.model.ModelUnavailableException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * In-memory registry of async daemon tasks. {@link #submit} starts the work on a virtual thread and
 * returns immediately with a task id; the work reports progress through an
 * {@link IngestProgressListener} that atomically swaps the task's {@link TaskSnapshot}, which a
 * client reads (lock-free) via {@link #find} or {@link #all}. Terminal tasks are evicted after a
 * short TTL so the map stays bounded; progress is not persisted across daemon restarts.
 *
 * <p>{@link #cancel} requests cooperative, best-effort cancellation: the per-task cancel flag is
 * checked on the next progress tick (raising {@link TaskCancelledException}) and the worker thread
 * is interrupted as a backstop. The task stops at the next checkpoint or when an in-flight model
 * call returns; memories already stored before that point remain.
 */
@Component
public class TaskRegistry {

  private static final Logger log = LoggerFactory.getLogger(TaskRegistry.class);

  /**
   * How long a finished task remains readable before eviction.
   */
  private static final Duration TERMINAL_TTL = Duration.ofMinutes(10);

  private final Map<UUID, Entry> tasks = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Per-task bookkeeping: the live snapshot, the worker {@link Future} (for interrupt-on-cancel),
   * the immutable display metadata, and the cooperative cancel flag.
   */
  private static final class Entry {
    final AtomicReference<TaskSnapshot> ref;
    final String kind;
    final String profile;
    volatile Future<?> future;
    volatile boolean cancelRequested;

    Entry(AtomicReference<TaskSnapshot> ref, String kind, String profile) {
      this.ref = ref;
      this.kind = kind;
      this.profile = profile;
    }
  }

  /** Immutable view of a task for listing: its id, display metadata, and current snapshot. */
  public record TaskInfo(UUID id, String kind, String profile, TaskSnapshot snapshot) {}

  /** Outcome of a {@link #cancel} request. */
  public enum CancelOutcome { CANCELLED, ALREADY_TERMINAL, NOT_FOUND }

  /**
   * Register and start a task tagged with {@code kind} (e.g. {@code "ingest"}, {@code "code"}, or a
   * caller-supplied label) and {@code profile}. {@code work} receives a progress listener and
   * returns the terminal result payload. A {@link TaskCancelledException} is recorded as
   * {@code CANCELLED}; a {@link ModelUnavailableException} as {@code model-unavailable}; any other
   * failure as {@code failure}.
   */
  public UUID submit(String kind, String profile, Function<IngestProgressListener, JsonNode> work) {
    UUID id = UUID.randomUUID();
    AtomicReference<TaskSnapshot> ref = new AtomicReference<>(TaskSnapshot.running(Instant.now()));
    Entry entry = new Entry(ref, kind, profile);
    tasks.put(id, entry);
    evictExpired();

    IngestProgressListener listener = (phase, done, total) -> {
      if (entry.cancelRequested) {
        throw new TaskCancelledException();
      }
      ref.updateAndGet(s -> s.withProgress(phase, done, total));
    };

    entry.future = executor.submit(() -> {
      try {
        JsonNode result = work.apply(listener);
        ref.updateAndGet(s -> s.succeeded(result, Instant.now()));
      } catch (TaskCancelledException e) {
        log.info("task {} cancelled", id);
        ref.updateAndGet(s -> s.cancelled(Instant.now()));
      } catch (ModelUnavailableException e) {
        // Classify and log the full cause chain: the bare wrapper message ("model extraction failed")
        // hid the real HTTP status / connection error that explains the failure.
        String reason = ModelFailures.describe(e);
        log.warn("task {} failed: model unavailable: {}", id, reason, e);
        ref.updateAndGet(s -> s.failed("model-unavailable", reason, Instant.now()));
      } catch (RuntimeException e) {
        // An interrupt backstop may surface as a wrapped interruption rather than our cancel signal.
        if (entry.cancelRequested) {
          log.info("task {} cancelled", id);
          ref.updateAndGet(s -> s.cancelled(Instant.now()));
        } else {
          log.warn("task {} failed", id, e);
          ref.updateAndGet(s -> s.failed("failure", e.getMessage(), Instant.now()));
        }
      }
    });

    return id;
  }

  /**
   * The current snapshot for {@code id}, or empty if unknown or already evicted.
   */
  public Optional<TaskSnapshot> find(UUID id) {
    evictExpired();
    Entry entry = tasks.get(id);
    return Optional.ofNullable(entry).map(e -> e.ref.get());
  }

  /**
   * All known tasks (running and recently-finished within the TTL window), newest first.
   */
  public List<TaskInfo> all() {
    evictExpired();
    List<TaskInfo> infos = new ArrayList<>(tasks.size());
    tasks.forEach((id, entry) -> infos.add(new TaskInfo(id, entry.kind, entry.profile, entry.ref.get())));
    infos.sort(Comparator.comparing((TaskInfo i) -> i.snapshot().startedAt()).reversed());
    return infos;
  }

  /**
   * Request cooperative cancellation of {@code id}. Returns {@code NOT_FOUND} when unknown,
   * {@code ALREADY_TERMINAL} when the task has already finished, or {@code CANCELLED} when a running
   * task was signalled to stop.
   */
  public CancelOutcome cancel(UUID id) {
    evictExpired();
    Entry entry = tasks.get(id);
    if (entry == null) {
      return CancelOutcome.NOT_FOUND;
    }
    if (entry.ref.get().isTerminal()) {
      return CancelOutcome.ALREADY_TERMINAL;
    }
    entry.cancelRequested = true;
    Future<?> future = entry.future;
    if (future != null) {
      future.cancel(true);
    }
    return CancelOutcome.CANCELLED;
  }

  private void evictExpired() {
    Instant cutoff = Instant.now().minus(TERMINAL_TTL);
    tasks.entrySet().removeIf(e -> {
      TaskSnapshot s = e.getValue().ref.get();
      return s.isTerminal() && s.finishedAt() != null && s.finishedAt().isBefore(cutoff);
    });
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }
}
