package dev.alvo.pieria.harvest;

import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.HarvestProperties;
import dev.alvo.pieria.harvester.process.HarvesterProcess;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.ProtocolViolation;
import dev.alvo.pieria.model.ModelUnavailableException;
import dev.alvo.pieria.task.TaskCancelledException;
import dev.alvo.pieria.task.TaskLane;
import dev.alvo.pieria.task.TaskProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Runs a harvester once and applies what it reports. Records are handled one at a time as they arrive,
 * which is what makes a checkpoint safe to save: everything before it has already been stored.
 */
@Service
public class HarvestRunService {

  private static final Logger log = LoggerFactory.getLogger(HarvestRunService.class);
  private static final int MAX_STDERR_TAIL_CHARS = 4_000;
  private static final String SUBCOMMAND = "harvest";
  private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(60);

  private final HarvestStore store;
  private final HarvesterRegistry registry;
  private final HarvestSecretStore secretStore;
  private final HarvestItemIngestor ingestor;
  private final HarvestProperties properties;
  private final EffectiveConfigResolver configResolver;
  private volatile boolean shuttingDown;

  public HarvestRunService(HarvestStore store, HarvesterRegistry registry, HarvestSecretStore secretStore,
                           HarvestItemIngestor ingestor, HarvestProperties properties, EffectiveConfigResolver configResolver) {
    this.store = store;
    this.registry = registry;
    this.secretStore = secretStore;
    this.ingestor = ingestor;
    this.properties = properties;
    this.configResolver = configResolver;
  }

  /** Runs stopped from now on are left to resume after the restart rather than counted as cancelled. */
  public void beginShutdown() {
    shuttingDown = true;
  }

  public HarvestRunSummary run(String sourceId, boolean dryRun, TaskProgress progress) {
    HarvestSource source = store.findSource(sourceId)
      .orElseThrow(() -> new IllegalArgumentException("unknown harvest source: " + sourceId));
    return new Run(source, store.startRun(sourceId, dryRun), dryRun, progress).execute();
  }

  /** Runs the harvester's own check of its config and credentials. */
  public SourceCheckResult check(String sourceId) {
    HarvestSource source = store.findSource(sourceId)
      .orElseThrow(() -> new IllegalArgumentException("unknown harvest source: " + sourceId));
    Set<String> secretValues = new HashSet<>();
    try {
      RegisteredHarvester harvester = registry.find(source.harvesterId()).orElseThrow(() -> new RunFailure(ErrorClass.CONFIG,
        "harvester " + source.harvesterId() + " is not installed or does not match its pinned checksum", null));
      Set<String> declared = HarvestEnvelope.declaredSecrets(harvester);
      HarvestRequest request = envelope(source, declared, secretValues);
      registry.requirePinned(harvester);
      Duration limit = CHECK_TIMEOUT.compareTo(properties.runTimeout()) < 0 ? CHECK_TIMEOUT : properties.runTimeout();
      Instant deadline = Instant.now().plus(limit);
      try (HarvesterProcess process = HarvesterProcess.start(harvester.command(), "check", harvester.directory(), request)) {
        HarvestRecord.Failure reported = null;
        for (HarvestRecord record = process.next(deadline); record != null; record = process.next(deadline)) {
          if (record instanceof HarvestRecord.Failure failure) {
            reported = failure;
          } else if (record instanceof HarvestRecord.SecretUpdate update) {
            HarvestEnvelope.saveSecret(secretStore, source, declared, secretValues, update);
          }
        }
        if (!process.awaitExit(deadline)) {
          throw new TimeoutException();
        }
        if (reported != null) {
          return new SourceCheckResult(false, reported.errorClass(), scrub(reported.message(), secretValues));
        }
        if (process.exitCode() != 0) {
          return new SourceCheckResult(false, ErrorClass.FATAL, "the harvester exited with code " + process.exitCode());
        }
        return SourceCheckResult.passed();
      }
    } catch (RunFailure e) {
      return new SourceCheckResult(false, e.errorClass, scrub(e.getMessage(), secretValues));
    } catch (HarvesterIntegrityException e) {
      return new SourceCheckResult(false, ErrorClass.CONFIG, e.getMessage());
    } catch (ProtocolViolation e) {
      return new SourceCheckResult(false, ErrorClass.FATAL, "protocol violation: " + scrub(e.getMessage(), secretValues));
    } catch (TimeoutException e) {
      return new SourceCheckResult(false, ErrorClass.TRANSIENT, "the check timed out");
    } catch (IOException e) {
      return new SourceCheckResult(false, ErrorClass.FATAL, "could not start the harvester: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new SourceCheckResult(false, ErrorClass.TRANSIENT, "the check was interrupted");
    }
  }

  private HarvestRequest envelope(HarvestSource source, Set<String> declared, Set<String> secretValues) throws RunFailure {
    try {
      return HarvestEnvelope.build(secretStore, source, declared, secretValues);
    } catch (HarvestEnvelope.InvalidSource e) {
      throw new RunFailure(ErrorClass.CONFIG, e.getMessage(), null);
    }
  }

  private static String scrub(String text, Set<String> secretValues) {
    return HarvestEnvelope.scrub(text, secretValues);
  }

  /** A run that could not finish, with the class that decides how it is retried. */
  private static final class RunFailure extends Exception {
    final ErrorClass errorClass;
    final Long retryAfterSeconds;

    RunFailure(ErrorClass errorClass, String message, Long retryAfterSeconds) {
      super(message);
      this.errorClass = errorClass;
      this.retryAfterSeconds = retryAfterSeconds;
    }
  }

  private final class Run {
    private final HarvestSource source;
    private final String runId;
    private final boolean dryRun;
    private final TaskProgress progress;
    private final TaskLane lane;
    private final String profileName;
    private final Set<String> secretValues = new HashSet<>();
    private final Set<String> declaredSecrets = new HashSet<>();
    private final Set<String> sessions = new LinkedHashSet<>();

    private long itemsSeen;
    private long itemsReported;
    private long itemsChanged;
    private long itemsDeleted;
    private long memoriesStored;
    private long chars;
    private long changedChars;
    /** Set once an item's extraction couldn't be read, so no later checkpoint can skip past it. */
    private boolean cursorHeld;
    private boolean sweepRequested;
    private boolean sweepApplied;
    private Long sweepBlockedCount;
    private HarvestRecord.Failure reportedFailure;
    private String stderr = "";

    Run(HarvestSource source, String runId, boolean dryRun, TaskProgress progress) {
      this.source = source;
      this.runId = runId;
      this.dryRun = dryRun;
      this.progress = progress;
      this.lane = progress.lane("harvest");
      this.profileName = store.profileName(source.profileId());
    }

    HarvestRunSummary execute() {
      try {
        lane.start();
        drive();
        HarvestRunSummary summary = finish(RunStatus.SUCCEEDED, null, null, null);
        lane.complete();
        return summary;
      } catch (RunFailure e) {
        return failed(e.errorClass, e.getMessage(), e.retryAfterSeconds);
      } catch (TaskCancelledException e) {
        finish(stoppedStatus(), null, null, null);
        throw e;
      } catch (ModelUnavailableException e) {
        rethrowIfStopped();
        return failed(ErrorClass.TRANSIENT, e.getMessage(), null);
      } catch (RuntimeException e) {
        rethrowIfStopped();
        log.warn("harvest run failed source={}", source.id(), e);
        return failed(ErrorClass.FATAL, e.toString(), null);
      }
    }

    private RunStatus stoppedStatus() {
      return shuttingDown ? RunStatus.INTERRUPTED : RunStatus.CANCELLED;
    }

    /** A cancel or shutdown during a model call surfaces as that call failing. */
    private void rethrowIfStopped() {
      try {
        if (shuttingDown) {
          throw new TaskCancelledException();
        }
        progress.checkCancelled();
      } catch (TaskCancelledException stopped) {
        finish(stoppedStatus(), null, null, null);
        throw stopped;
      }
    }

    private HarvestRunSummary failed(ErrorClass errorClass, String message, Long retryAfterSeconds) {
      if (errorClass == ErrorClass.AUTH) {
        store.setStatus(source.id(), SourceStatus.NEEDS_AUTH);
      }
      HarvestRunSummary summary = finish(RunStatus.FAILED, errorClass, message, retryAfterSeconds);
      try {
        lane.fail();
      } catch (TaskCancelledException e) {
        // The run is already recorded; a late cancel changes nothing.
      }
      return summary;
    }

    private void drive() throws RunFailure {
      RegisteredHarvester harvester = registry.find(source.harvesterId()).orElseThrow(() -> new RunFailure(ErrorClass.CONFIG,
        "harvester " + source.harvesterId() + " is not installed or does not match its pinned checksum", null));
      declaredSecrets.addAll(HarvestEnvelope.declaredSecrets(harvester));
      HarvestRequest request = envelope(source, declaredSecrets, secretValues);
      Instant deadline = Instant.now().plus(properties.runTimeout());

      HarvesterProcess process;
      try {
        registry.requirePinned(harvester);
        process = HarvesterProcess.start(harvester.command(), SUBCOMMAND, harvester.directory(), request);
      } catch (HarvesterIntegrityException e) {
        throw new RunFailure(ErrorClass.CONFIG, e.getMessage(), null);
      } catch (IOException e) {
        throw new RunFailure(ErrorClass.FATAL, "could not start the harvester: " + e.getMessage(), null);
      }

      try (process) {
        try {
          readRecords(process, deadline);
        } finally {
          process.destroy();
          stderr = process.stderr();
        }
      } catch (ProtocolViolation e) {
        throw new RunFailure(ErrorClass.FATAL, "protocol violation: " + e.getMessage(), null);
      } catch (TimeoutException e) {
        throw new RunFailure(ErrorClass.TRANSIENT, "the run timed out", null);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new TaskCancelledException();
      }
      if (sweepRequested && !dryRun) {
        applySweep();
      }
    }

    private void readRecords(HarvesterProcess process, Instant deadline)
      throws TimeoutException, InterruptedException, RunFailure {
      for (HarvestRecord record = process.next(deadline); record != null; record = process.next(deadline)) {
        progress.checkCancelled();
        apply(record);
      }
      if (!process.awaitExit(deadline)) {
        throw new TimeoutException();
      }
      if (reportedFailure != null) {
        throw new RunFailure(reportedFailure.errorClass(), reportedFailure.message(), reportedFailure.retryAfterSeconds());
      }
      if (process.exitCode() != 0) {
        throw new RunFailure(ErrorClass.FATAL, "the harvester exited with code " + process.exitCode(), null);
      }
    }

    private void apply(HarvestRecord record) throws RunFailure {
      switch (record) {
        case HarvestRecord.Item item -> applyItem(item);
        case HarvestRecord.Seen seen -> {
          itemsSeen += seen.ids().size();
          if (!dryRun) {
            store.markSeen(source.id(), seen.ids(), runId);
          }
        }
        case HarvestRecord.Tombstone tombstone -> {
          if (!dryRun && ingestor.delete(source, tombstone.id())) {
            itemsDeleted++;
          }
        }
        case HarvestRecord.Checkpoint checkpoint -> {
          if (!dryRun && !cursorHeld) {
            store.saveCursor(source.id(), checkpoint.cursor().toString());
          }
        }
        case HarvestRecord.SecretUpdate update -> applySecret(update);
        case HarvestRecord.Sweep ignored -> sweepRequested = true;
        case HarvestRecord.Progress update ->
          lane.onPhase("harvest", (int) Math.min(update.done(), Integer.MAX_VALUE),
            update.total() == null ? 0 : (int) Math.min(update.total(), Integer.MAX_VALUE));
        case HarvestRecord.Failure failure -> reportedFailure = failure;
        case HarvestRecord.DeviceCode ignored ->
          throw new RunFailure(ErrorClass.FATAL, "protocol violation: harvest may not emit device_code", null);
      }
    }

    private void requireLive() throws RunFailure {
      if (store.findSource(source.id()).isEmpty()) {
        throw new RunFailure(ErrorClass.CONFIG, "the source was removed during the run", null);
      }
    }

    /** The harvester is asked to honour its limits; this holds it to them, as the daemon pays for extraction. */
    private void enforceLimits(HarvestRecord.Item item) throws RunFailure {
      if (source.maxItemsPerRun() != null && itemsReported >= source.maxItemsPerRun()) {
        throw new RunFailure(ErrorClass.FATAL, "the harvester exceeded maxItemsPerRun", null);
      }
      if (source.maxCharsPerRun() != null && itemsReported > 0 && chars + itemChars(item) > source.maxCharsPerRun()) {
        throw new RunFailure(ErrorClass.FATAL, "the harvester exceeded maxCharsPerRun", null);
      }
    }

    private static long itemChars(HarvestRecord.Item item) {
      return switch (item) {
        case HarvestRecord.Document document -> document.text().length();
        case HarvestRecord.ConversationThread thread -> thread.turns().stream().mapToLong(turn -> turn.text().length()).sum();
      };
    }

    private void applyItem(HarvestRecord.Item item) throws RunFailure {
      requireLive();
      enforceLimits(item);
      itemsReported++;
      ItemOutcome outcome = dryRun
        ? ingestor.preview(source, item)
        : ingestor.ingest(source, profileName, item, runId, lane);
      itemsSeen++;
      chars += outcome.chars();
      if (outcome.changed()) {
        itemsChanged++;
        changedChars += outcome.chars();
      }
      memoriesStored += outcome.memoriesStored();
      cursorHeld |= !outcome.complete();
      if (outcome.memoriesStored() > 0) {
        sessions.add(outcome.sessionId());
      }
    }

    /** Saved on dry runs too, because the provider has already replaced the old value. */
    private void applySecret(HarvestRecord.SecretUpdate update) {
      HarvestEnvelope.saveSecret(secretStore, source, declaredSecrets, secretValues, update);
    }

    private void applySweep() {
      List<HarvestItem> unseen = store.unseenItems(source.id(), runId);
      long live = store.liveItemCount(source.id());
      Long acceptedCount = store.acceptedSweepCount(source.id());
      boolean accepted = acceptedCount != null && unseen.size() <= acceptedCount;
      boolean wouldEmptySource = !unseen.isEmpty() && unseen.size() == live;
      if (!accepted && (wouldEmptySource || unseen.size() > Math.max(properties.sweepGuardFloor(), properties.sweepGuardShare() * live))) {
        log.warn("harvest source={} sweep would delete {} of {} items; holding it back", source.id(), unseen.size(), live);
        sweepBlockedCount = (long) unseen.size();
        store.setSweepBlocked(source.id(), sweepBlockedCount);
        return;
      }
      unseen.forEach(item -> ingestor.delete(source, item.externalId()));
      itemsDeleted += unseen.size();
      sweepApplied = true;
      store.setSweepBlocked(source.id(), null);
      if (acceptedCount != null) {
        store.setAcceptedSweepCount(source.id(), null);
      }
    }

    private long estimatedChunks() {
      int chunkChars = Math.max(1, configResolver.resolve(source.profileId()).ingestion().chunkSizeChars());
      return (changedChars + chunkChars - 1) / chunkChars;
    }

    private HarvestRunSummary finish(RunStatus status, ErrorClass errorClass, String message, Long retryAfterSeconds) {
      String cleanMessage = scrub(message, secretValues);
      String cleanStderr = scrub(stderr, secretValues);
      String tail = cleanStderr.length() <= MAX_STDERR_TAIL_CHARS
        ? cleanStderr : cleanStderr.substring(cleanStderr.length() - MAX_STDERR_TAIL_CHARS);
      store.finishRun(new HarvestRun(runId, source.id(), dryRun, status, null, Instant.now(), itemsSeen, itemsChanged, itemsDeleted,
        memoriesStored, chars, errorClass == null ? null : errorClass.wire(), cleanMessage, tail));
      return new HarvestRunSummary(runId, status, itemsSeen, itemsChanged, itemsDeleted, memoriesStored, chars, estimatedChunks(),
        sweepApplied, sweepBlockedCount, errorClass, cleanMessage, retryAfterSeconds, new ArrayList<>(sessions));
    }
  }
}
