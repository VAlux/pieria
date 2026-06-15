package dev.alvo.pieria.task;

import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.model.ModelUnavailableException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * In-memory registry of async daemon tasks. {@link #submit} starts the work on a virtual thread and
 * returns immediately with a task id; the work reports progress through an
 * {@link IngestProgressListener} that atomically swaps the task's {@link TaskSnapshot}, which a
 * client reads (lock-free) via {@link #find}. Terminal tasks are evicted after a short TTL so the
 * map stays bounded; progress is not persisted across daemon restarts.
 */
@Component
public class TaskRegistry {

  private static final Logger log = LoggerFactory.getLogger(TaskRegistry.class);

  /**
   * How long a finished task remains readable before eviction.
   */
  private static final Duration TERMINAL_TTL = Duration.ofMinutes(10);

  private final Map<UUID, AtomicReference<TaskSnapshot>> tasks = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Register and start a task. {@code work} receives a progress listener and returns the terminal
   * result payload. A {@link ModelUnavailableException} is recorded as {@code model-unavailable};
   * any other failure as {@code failure}.
   */
  public UUID submit(Function<IngestProgressListener, JsonNode> work) {
    UUID id = UUID.randomUUID();
    AtomicReference<TaskSnapshot> ref = new AtomicReference<>(TaskSnapshot.running(Instant.now()));
    tasks.put(id, ref);
    evictExpired();

    IngestProgressListener listener =
      (phase, done, total) -> ref.updateAndGet(s -> s.withProgress(phase, done, total));

    executor.submit(() -> {
      try {
        JsonNode result = work.apply(listener);
        ref.updateAndGet(s -> s.succeeded(result, Instant.now()));
      } catch (ModelUnavailableException e) {
        log.warn("task {} failed: model unavailable", id);
        ref.updateAndGet(s -> s.failed("model-unavailable", e.getMessage(), Instant.now()));
      } catch (RuntimeException e) {
        log.warn("task {} failed", id, e);
        ref.updateAndGet(s -> s.failed("failure", e.getMessage(), Instant.now()));
      }
    });

    return id;
  }

  /**
   * The current snapshot for {@code id}, or empty if unknown or already evicted.
   */
  public Optional<TaskSnapshot> find(UUID id) {
    evictExpired();
    AtomicReference<TaskSnapshot> ref = tasks.get(id);
    return Optional.ofNullable(ref).map(AtomicReference::get);
  }

  private void evictExpired() {
    Instant cutoff = Instant.now().minus(TERMINAL_TTL);
    tasks.entrySet().removeIf(e -> {
      TaskSnapshot s = e.getValue().get();
      return s.isTerminal() && s.finishedAt() != null && s.finishedAt().isBefore(cutoff);
    });
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }
}
