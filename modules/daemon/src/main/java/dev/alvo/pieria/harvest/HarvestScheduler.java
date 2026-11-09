package dev.alvo.pieria.harvest;

import dev.alvo.pieria.config.HarvestProperties;
import dev.alvo.pieria.domain.error.ConflictException;
import dev.alvo.pieria.reminiscence.ReminiscenceService;
import dev.alvo.pieria.task.TaskCancelledException;
import dev.alvo.pieria.task.TaskLane;
import dev.alvo.pieria.task.TaskProgress;
import dev.alvo.pieria.task.TaskRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts harvest runs when sources come due, up to the configured number at a time. */
@Component
public class HarvestScheduler {

  private static final Logger log = LoggerFactory.getLogger(HarvestScheduler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long STOP_POLL_MILLIS = 50;

  private final HarvestStore store;
  private final HarvestRunService runs;
  private final TaskRegistry tasks;
  private final HarvestProperties properties;
  private final ReminiscenceService reminiscence;
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
  private final Map<String, UUID> runningTasks = new ConcurrentHashMap<>();
  private volatile boolean shuttingDown;

  public HarvestScheduler(HarvestStore store, HarvestRunService runs, TaskRegistry tasks, HarvestProperties properties,
                          ReminiscenceService reminiscence) {
    this.store = store;
    this.runs = runs;
    this.tasks = tasks;
    this.properties = properties;
    this.reminiscence = reminiscence;
  }

  @Scheduled(fixedDelayString = "${pieria.harvest.poll-ms:30000}", initialDelayString = "${pieria.harvest.poll-ms:30000}")
  public void tick() {
    if (!properties.enabled()) {
      return;
    }
    // Sources already running are still due, so ask for enough to see past them.
    for (HarvestSource source : store.dueSources(Instant.now(), properties.maxConcurrentRuns() + inFlight.size())) {
      launch(source.id(), false);
    }
  }

  /** A run left over from a previous process is resumed from its last checkpoint at the next tick. */
  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    int interrupted = store.interruptRunningRuns(Instant.now());
    if (interrupted > 0) {
      log.info("harvest: marked {} unfinished runs as interrupted", interrupted);
    }
  }

  /** Runs this interrupts are made due at once, to resume from their last checkpoint after the restart. */
  @PreDestroy
  public void shutdown() {
    shuttingDown = true;
    runs.beginShutdown();
  }

  /** Starts a run, or returns empty when the source is unknown or running, or capacity is full. */
  public synchronized Optional<UUID> launch(String sourceId, boolean dryRun) {
    Optional<HarvestSource> found = store.findSource(sourceId);
    if (found.isEmpty() || !claim(sourceId)) {
      return Optional.empty();
    }
    String profile = store.profileName(found.get().profileId());
    AtomicBoolean claimedByLaunch = new AtomicBoolean(true);
    UUID task = tasks.submit("harvest", profile, progress -> {
      // A retry of this task re-enters here, and must take its own claim.
      if (!claimedByLaunch.getAndSet(false) && !claim(sourceId)) {
        throw new ConflictException("harvest source " + sourceId + " is already running, or no run slot is free");
      }
      return execute(sourceId, profile, dryRun, progress);
    });
    runningTasks.put(sourceId, task);
    return Optional.of(task);
  }

  public boolean isRunning(String sourceId) {
    return inFlight.contains(sourceId);
  }

  /** Cancels the source's run, if any, and reports whether it ended within {@code timeout}. */
  public boolean stopAndWait(String sourceId, Duration timeout) {
    UUID task = runningTasks.get(sourceId);
    if (task != null && inFlight.contains(sourceId)) {
      tasks.cancel(task);
    }
    Instant deadline = Instant.now().plus(timeout);
    while (inFlight.contains(sourceId) && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(STOP_POLL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return !inFlight.contains(sourceId);
  }

  private synchronized boolean claim(String sourceId) {
    return inFlight.size() < properties.maxConcurrentRuns() && inFlight.add(sourceId);
  }

  private JsonNode execute(String sourceId, String profile, boolean dryRun, TaskProgress progress) {
    HarvestSource source = null;
    try {
      source = store.findSource(sourceId).orElseThrow(() -> new IllegalArgumentException("unknown harvest source: " + sourceId));
      HarvestRunSummary summary = runs.run(source.id(), dryRun, progress);
      if (!dryRun) {
        boolean succeeded = summary.status() == RunStatus.SUCCEEDED;
        reschedule(source, succeeded ? 0 : source.consecutiveFailures() + 1, summary.retryAfterSeconds());
        // Whatever a failed run wrote still counts, and those items will read as unchanged next time.
        if (source.mode() == SourceMode.FULL && !summary.sessions().isEmpty()) {
          submitGraphAdoption(profile, summary.sessions());
        }
      }
      return MAPPER.createObjectNode().put("source", source.id()).put("runId", summary.runId())
        .put("status", summary.status().name().toLowerCase(java.util.Locale.ROOT))
        .put("itemsSeen", summary.itemsSeen()).put("itemsChanged", summary.itemsChanged())
        .put("itemsDeleted", summary.itemsDeleted()).put("memoriesStored", summary.memoriesStored())
        .put("chars", summary.chars()).put("estimatedChunks", summary.estimatedChunks());
    } catch (TaskCancelledException e) {
      if (!dryRun && source != null) {
        if (shuttingDown) {
          store.scheduleNext(source.id(), Instant.now(), source.consecutiveFailures());
        } else {
          reschedule(source, source.consecutiveFailures(), null);
        }
      }
      throw e;
    } catch (RuntimeException e) {
      if (!dryRun && source != null) {
        reschedule(source, source.consecutiveFailures() + 1, null);
      }
      throw e;
    } finally {
      inFlight.remove(sourceId);
    }
  }

  private void reschedule(HarvestSource source, int consecutiveFailures, Long retryAfterSeconds) {
    store.scheduleNext(source.id(), HarvestSchedule.nextRunAt(Instant.now(), source.intervalSeconds(), consecutiveFailures,
      retryAfterSeconds, properties.maxBackoff(), ThreadLocalRandom.current().nextDouble()), consecutiveFailures);
  }

  private void submitGraphAdoption(String profile, List<String> sessions) {
    tasks.submit("harvest-graph", profile, progress -> {
      TaskLane graph = progress.lane("graph");
      graph.start();
      var result = reminiscence.adoptSessionOrphans(profile, sessions, graph);
      graph.complete();
      return MAPPER.createObjectNode().put("memoriesScanned", result.memoriesScanned()).put("memoriesAdopted", result.memoriesAdopted());
    });
  }
}
