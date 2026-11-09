package dev.alvo.pieria.harvest;

import dev.alvo.pieria.config.HarvestProperties;
import dev.alvo.pieria.reminiscence.ReminiscenceService;
import dev.alvo.pieria.task.TaskCancelledException;
import dev.alvo.pieria.task.TaskLane;
import dev.alvo.pieria.task.TaskProgress;
import dev.alvo.pieria.task.TaskRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Starts harvest runs when sources come due, up to the configured number at a time. */
@Component
public class HarvestScheduler {

  private static final Logger log = LoggerFactory.getLogger(HarvestScheduler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HarvestStore store;
  private final HarvestRunService runs;
  private final TaskRegistry tasks;
  private final HarvestProperties properties;
  private final ReminiscenceService reminiscence;
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

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

  /** Starts a run, or returns empty when the source is unknown or running, or capacity is full. */
  public synchronized Optional<UUID> launch(String sourceId, boolean dryRun) {
    Optional<HarvestSource> found = store.findSource(sourceId);
    if (found.isEmpty() || inFlight.size() >= properties.maxConcurrentRuns() || !inFlight.add(sourceId)) {
      return Optional.empty();
    }
    HarvestSource source = found.get();
    String profile = store.profileName(source.profileId());
    return Optional.of(tasks.submit("harvest", profile, progress -> execute(source, profile, dryRun, progress)));
  }

  private JsonNode execute(HarvestSource source, String profile, boolean dryRun, TaskProgress progress) {
    try {
      HarvestRunSummary summary = runs.run(source.id(), dryRun, progress);
      if (!dryRun) {
        boolean succeeded = summary.status() == RunStatus.SUCCEEDED;
        reschedule(source, succeeded ? 0 : source.consecutiveFailures() + 1, summary.retryAfterSeconds());
        if (succeeded && source.mode() == SourceMode.FULL && summary.memoriesStored() > 0 && !summary.sessions().isEmpty()) {
          submitGraphAdoption(profile, summary.sessions());
        }
      }
      return MAPPER.createObjectNode().put("source", source.id()).put("runId", summary.runId())
        .put("status", summary.status().name().toLowerCase(java.util.Locale.ROOT))
        .put("itemsSeen", summary.itemsSeen()).put("itemsChanged", summary.itemsChanged())
        .put("itemsDeleted", summary.itemsDeleted()).put("memoriesStored", summary.memoriesStored())
        .put("estimatedChunks", summary.estimatedChunks());
    } catch (TaskCancelledException e) {
      if (!dryRun) {
        reschedule(source, source.consecutiveFailures(), null);
      }
      throw e;
    } catch (RuntimeException e) {
      if (!dryRun) {
        reschedule(source, source.consecutiveFailures() + 1, null);
      }
      throw e;
    } finally {
      inFlight.remove(source.id());
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
