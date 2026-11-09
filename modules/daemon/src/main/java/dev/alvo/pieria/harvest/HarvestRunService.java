package dev.alvo.pieria.harvest;

import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.HarvestProperties;
import dev.alvo.pieria.harvester.manifest.ConfigFieldSpec;
import dev.alvo.pieria.harvester.manifest.FieldKind;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Runs a harvester once and applies what it reports. Records are handled one at a time as they arrive,
 * which is what makes a checkpoint safe to save: everything before it has already been stored.
 */
@Service
public class HarvestRunService {

  private static final Logger log = LoggerFactory.getLogger(HarvestRunService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_STDERR_TAIL_CHARS = 4_000;
  private static final String SUBCOMMAND = "harvest";

  private final HarvestStore store;
  private final HarvesterRegistry registry;
  private final HarvestSecretStore secretStore;
  private final HarvestItemIngestor ingestor;
  private final HarvestProperties properties;
  private final EffectiveConfigResolver configResolver;

  public HarvestRunService(HarvestStore store, HarvesterRegistry registry, HarvestSecretStore secretStore,
                           HarvestItemIngestor ingestor, HarvestProperties properties, EffectiveConfigResolver configResolver) {
    this.store = store;
    this.registry = registry;
    this.secretStore = secretStore;
    this.ingestor = ingestor;
    this.properties = properties;
    this.configResolver = configResolver;
  }

  public HarvestRunSummary run(String sourceId, boolean dryRun, TaskProgress progress) {
    HarvestSource source = store.findSource(sourceId)
      .orElseThrow(() -> new IllegalArgumentException("unknown harvest source: " + sourceId));
    return new Run(source, store.startRun(sourceId, dryRun), dryRun, progress).execute();
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
    private long itemsChanged;
    private long itemsDeleted;
    private long memoriesStored;
    private long chars;
    private long changedChars;
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
        finish(RunStatus.CANCELLED, null, null, null);
        throw e;
      } catch (ModelUnavailableException e) {
        return failed(ErrorClass.TRANSIENT, e.getMessage(), null);
      } catch (RuntimeException e) {
        try {
          progress.checkCancelled();
        } catch (TaskCancelledException cancelled) {
          finish(RunStatus.CANCELLED, null, null, null);
          throw cancelled;
        }
        log.warn("harvest run failed source={}", source.id(), e);
        return failed(ErrorClass.FATAL, e.toString(), null);
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
      HarvestRequest request = request(harvester);
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

    private HarvestRequest request(RegisteredHarvester harvester) throws RunFailure {
      harvester.manifest().config().stream()
        .filter(field -> field.kind() == FieldKind.SECRET)
        .map(ConfigFieldSpec::key)
        .forEach(declaredSecrets::add);
      Map<String, String> secrets = new LinkedHashMap<>(secretStore.secrets(source.id()));
      secretValues.addAll(secrets.values());
      secrets.keySet().retainAll(declaredSecrets);
      try {
        if (!(MAPPER.readTree(source.configJson()) instanceof ObjectNode config)) {
          throw new RunFailure(ErrorClass.CONFIG, "the source config is not a JSON object", null);
        }
        JsonNode cursor = source.cursorJson() == null ? null : MAPPER.readTree(source.cursorJson());
        return new HarvestRequest(HarvestRequest.PROTOCOL_VERSION, config, secrets, cursor,
          new HarvestRequest.Limits(source.maxItemsPerRun(), source.maxCharsPerRun()));
      } catch (JacksonException e) {
        throw new RunFailure(ErrorClass.CONFIG, "the source config or cursor is not valid JSON", null);
      }
    }

    private void apply(HarvestRecord record) {
      switch (record) {
        case HarvestRecord.Item item -> applyItem(item);
        case HarvestRecord.Seen seen -> {
          itemsSeen += seen.ids().size();
          if (!dryRun) {
            store.markSeen(source.id(), seen.ids(), runId);
          }
        }
        case HarvestRecord.Tombstone tombstone -> {
          if (!dryRun) {
            ingestor.delete(source, tombstone.id());
            itemsDeleted++;
          }
        }
        case HarvestRecord.Checkpoint checkpoint -> {
          if (!dryRun) {
            store.saveCursor(source.id(), checkpoint.cursor().toString());
          }
        }
        case HarvestRecord.SecretUpdate update -> applySecret(update);
        case HarvestRecord.Sweep ignored -> sweepRequested = true;
        case HarvestRecord.Progress update ->
          lane.onPhase("harvest", (int) Math.min(update.done(), Integer.MAX_VALUE),
            update.total() == null ? 0 : (int) Math.min(update.total(), Integer.MAX_VALUE));
        case HarvestRecord.Failure failure -> reportedFailure = failure;
      }
    }

    private void applyItem(HarvestRecord.Item item) {
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
      if (outcome.memoriesStored() > 0) {
        sessions.add(outcome.sessionId());
      }
    }

    /** Saved on dry runs too, because the provider has already replaced the old value. */
    private void applySecret(HarvestRecord.SecretUpdate update) {
      secretValues.add(update.value());
      if (declaredSecrets.contains(update.name())) {
        secretStore.put(source.id(), update.name(), update.value());
      } else {
        log.warn("harvest source={} sent an update for undeclared secret {}; ignoring", source.id(), update.name());
      }
    }

    private void applySweep() {
      List<HarvestItem> unseen = store.unseenItems(source.id(), runId);
      long live = store.liveItemCount(source.id());
      if (unseen.size() > Math.max(properties.sweepGuardFloor(), properties.sweepGuardShare() * live)) {
        log.warn("harvest source={} sweep would delete {} of {} items; holding it back", source.id(), unseen.size(), live);
        sweepBlockedCount = (long) unseen.size();
        store.setSweepBlocked(source.id(), sweepBlockedCount);
        return;
      }
      unseen.forEach(item -> ingestor.delete(source, item.externalId()));
      itemsDeleted += unseen.size();
      sweepApplied = true;
      store.setSweepBlocked(source.id(), null);
    }

    private long estimatedChunks() {
      int chunkChars = Math.max(1, configResolver.resolve(source.profileId()).ingestion().chunkSizeChars());
      return (changedChars + chunkChars - 1) / chunkChars;
    }

    private String scrub(String text) {
      if (text == null) {
        return null;
      }
      String scrubbed = text;
      for (String secret : secretValues.stream().filter(value -> !value.isBlank()).collect(Collectors.toList())) {
        scrubbed = scrubbed.replace(secret, "***");
      }
      return scrubbed;
    }

    private HarvestRunSummary finish(RunStatus status, ErrorClass errorClass, String message, Long retryAfterSeconds) {
      String cleanMessage = scrub(message);
      String cleanStderr = scrub(stderr);
      String tail = cleanStderr.length() <= MAX_STDERR_TAIL_CHARS
        ? cleanStderr : cleanStderr.substring(cleanStderr.length() - MAX_STDERR_TAIL_CHARS);
      store.finishRun(new HarvestRun(runId, source.id(), dryRun, status, null, Instant.now(), itemsSeen, itemsChanged, itemsDeleted,
        memoriesStored, chars, errorClass == null ? null : errorClass.wire(), cleanMessage, tail));
      return new HarvestRunSummary(runId, status, itemsSeen, itemsChanged, itemsDeleted, memoriesStored, chars, estimatedChunks(),
        sweepApplied, sweepBlockedCount, errorClass, cleanMessage, retryAfterSeconds, new ArrayList<>(sessions));
    }
  }
}
