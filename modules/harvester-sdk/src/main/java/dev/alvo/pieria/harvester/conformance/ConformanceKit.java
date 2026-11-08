package dev.alvo.pieria.harvester.conformance;

import dev.alvo.pieria.harvester.conformance.ConformanceReport.Result;
import dev.alvo.pieria.harvester.conformance.ConformanceReport.Status;
import dev.alvo.pieria.harvester.conformance.ProcessRun.Outcome;
import dev.alvo.pieria.harvester.manifest.Capability;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.RecordCodec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests that a harvester follows the protocol by running it as a real process, so it works for a
 * harvester written in any language. Point it at a fixture or stub whose content does not change.
 */
public final class ConformanceKit {

  private static final Duration CLOSE_GRACE = Duration.ofSeconds(10);
  private static final int MAX_LIMITED_STEPS = 20;
  private static final String NOT_COMPLETED = "harvest did not complete";
  private static final List<String> OUTPUT_DEPENDENT_CHECKS = List.of(
    "harvest-emits-items", "capabilities-declared", "ids-stable-across-runs", "resume-loses-nothing",
    "sweep-is-complete", "limit-respected", "exits-when-stdout-closes");

  private final ConformanceTarget target;
  private final ConformanceOptions options;
  private final List<Result> results = new ArrayList<>();
  private final List<Outcome> outcomes = new ArrayList<>();

  private ConformanceKit(ConformanceTarget target, ConformanceOptions options) {
    this.target = target;
    this.options = options;
  }

  public static ConformanceReport run(ConformanceTarget target, ConformanceOptions options) {
    ConformanceKit kit = new ConformanceKit(target, options);
    kit.runChecks();
    return new ConformanceReport(kit.results);
  }

  private HarvestRequest request(ObjectNode config, JsonNode cursor, HarvestRequest.Limits limits) {
    return new HarvestRequest(HarvestRequest.PROTOCOL_VERSION, config, options.secrets(), cursor, limits);
  }

  private Outcome launch(String command, ObjectNode config, JsonNode cursor, HarvestRequest.Limits limits) {
    Outcome outcome = ProcessRun.execute(target, command, request(config, cursor, limits), options.timeout());
    outcomes.add(outcome);
    return outcome;
  }

  private Outcome harvest(JsonNode cursor, HarvestRequest.Limits limits) {
    return launch("harvest", options.config(), cursor, limits);
  }

  private void record(String check, boolean passed, String failureDetail) {
    results.add(new Result(check, passed ? Status.PASS : Status.FAIL, passed ? "" : failureDetail));
  }

  private void skip(String check, String reason) {
    results.add(new Result(check, Status.SKIP, reason));
  }

  private void runChecks() {
    checkValidConfig();
    checkInvalidConfig();

    Outcome full = harvest(null, HarvestRequest.Limits.NONE);
    record("harvest-completes", full.completed(), full.describe());
    checkSecrets();
    if (!full.completed()) {
      OUTPUT_DEPENDENT_CHECKS.forEach(check -> record(check, false, NOT_COMPLETED));
      return;
    }

    Map<String, HarvestRecord.Item> items = items(full.records());
    record("harvest-emits-items", !items.isEmpty(), "no documents or threads were emitted");
    checkCapabilities(full);
    checkStableIds(items);
    List<Outcome> resumed = checkResume(full);
    checkSweeps(items, full, resumed);
    checkLimit(items);
    checkStdoutClose();
  }

  private void checkValidConfig() {
    Outcome outcome = launch("check", options.config(), null, HarvestRequest.Limits.NONE);
    boolean onlySecretUpdates = outcome.records().stream().allMatch(HarvestRecord.SecretUpdate.class::isInstance);
    if (outcome.completed() && !onlySecretUpdates) {
      record("check-accepts-valid-config", false, "check may only emit secret_update");
    } else {
      record("check-accepts-valid-config", outcome.completed(), outcome.describe());
    }
  }

  private void checkInvalidConfig() {
    String check = "check-rejects-invalid-config";
    if (options.invalidConfig() == null) {
      skip(check, "no invalid config was supplied");
      return;
    }
    Outcome outcome = launch("check", options.invalidConfig(), null, HarvestRequest.Limits.NONE);
    HarvestRecord.Failure failure = outcome.failure();
    boolean rejected = !outcome.timedOut() && outcome.exitCode() != 0 && failure != null
      && (failure.errorClass() == ErrorClass.AUTH || failure.errorClass() == ErrorClass.CONFIG);
    record(check, rejected, "expected an auth or config error, got: " + outcome.describe());
  }

  /** Runs over the launches so far. The detail never repeats the secret it found. */
  private void checkSecrets() {
    String check = "secrets-stay-private";
    List<String> secrets = options.secrets().values().stream().filter(value -> !value.isBlank()).toList();
    if (secrets.isEmpty()) {
      skip(check, "no secrets were supplied");
      return;
    }
    for (Outcome outcome : outcomes) {
      for (String secret : secrets) {
        boolean inRecords = outcome.records().stream()
          .filter(record -> !(record instanceof HarvestRecord.SecretUpdate))
          .anyMatch(record -> RecordCodec.encode(record).contains(secret));
        if (inRecords || outcome.stderr().contains(secret)) {
          record(check, false, "a secret value appeared in " + (inRecords ? "a record" : "stderr"));
          return;
        }
      }
    }
    record(check, true, "");
  }

  private void checkCapabilities(Outcome full) {
    boolean deletes = full.records().stream()
      .anyMatch(r -> r instanceof HarvestRecord.Tombstone || r instanceof HarvestRecord.Sweep);
    if (deletes && !target.manifest().has(Capability.DELETES)) {
      record("capabilities-declared", false, "emitted tombstone or sweep without the deletes capability");
    } else if (target.manifest().has(Capability.INCREMENTAL) && lastCursor(full.records()) == null) {
      record("capabilities-declared", false, "declares incremental but emitted no checkpoint");
    } else {
      record("capabilities-declared", true, "");
    }
  }

  private void checkStableIds(Map<String, HarvestRecord.Item> items) {
    Outcome again = harvest(null, HarvestRequest.Limits.NONE);
    if (!again.completed()) {
      record("ids-stable-across-runs", false, "second run: " + again.describe());
      return;
    }
    record("ids-stable-across-runs", items.equals(items(again.records())),
      "two runs over the same source produced different items");
  }

  /**
   * Resuming is the normal path for every scheduled run, so the last checkpoint must always work,
   * even with nothing new after it. Earlier checkpoints must also lose nothing. Returns the resumed
   * runs for the sweep check.
   */
  private List<Outcome> checkResume(Outcome full) {
    String check = "resume-loses-nothing";
    if (!target.manifest().has(Capability.INCREMENTAL)) {
      skip(check, "the incremental capability is not declared");
      return List.of();
    }
    JsonNode endCursor = lastCursor(full.records());
    if (endCursor == null) {
      skip(check, "no checkpoint was emitted");
      return List.of();
    }
    List<Outcome> resumed = new ArrayList<>();
    Outcome fromEnd = harvest(endCursor, HarvestRequest.Limits.NONE);
    if (!fromEnd.completed()) {
      record(check, false, "resuming from the last checkpoint: " + fromEnd.describe());
      return resumed;
    }
    resumed.add(fromEnd);

    List<HarvestRecord> records = full.records();
    List<Integer> candidates = new ArrayList<>();
    for (int i = 0; i < records.size(); i++) {
      if (records.get(i) instanceof HarvestRecord.Checkpoint && !items(records.subList(i + 1, records.size())).isEmpty()) {
        candidates.add(i);
      }
    }
    Set<Integer> sampled = candidates.isEmpty() ? Set.of() : new LinkedHashSet<>(List.of(candidates.getFirst(), candidates.get(candidates.size() / 2)));
    for (int checkpoint : sampled) {
      Outcome run = harvest(((HarvestRecord.Checkpoint) records.get(checkpoint)).cursor(), HarvestRequest.Limits.NONE);
      if (!run.completed()) {
        record(check, false, "resumed run: " + run.describe());
        return resumed;
      }
      resumed.add(run);
      Set<String> missing = new LinkedHashSet<>(items(records.subList(checkpoint + 1, records.size())).keySet());
      missing.removeAll(items(run.records()).keySet());
      if (!missing.isEmpty()) {
        record(check, false, "items after the checkpoint were lost on resume: " + missing);
        return resumed;
      }
    }
    record(check, true, "");
    return resumed;
  }

  private void checkSweeps(Map<String, HarvestRecord.Item> items, Outcome full, List<Outcome> resumed) {
    List<Outcome> runs = new ArrayList<>(resumed);
    runs.add(full);
    for (Outcome run : runs) {
      String problem = sweepProblem(run.records(), items);
      if (problem != null) {
        record("sweep-is-complete", false, problem);
        return;
      }
    }
    record("sweep-is-complete", true, "");
  }

  /** Why this run's sweep would be unsafe, or null when it is fine or there is none. */
  private static String sweepProblem(List<HarvestRecord> records, Map<String, HarvestRecord.Item> items) {
    int sweep = -1;
    for (int i = 0; i < records.size(); i++) {
      if (records.get(i) instanceof HarvestRecord.Sweep) {
        sweep = i;
        break;
      }
    }
    if (sweep < 0) {
      return null;
    }
    boolean last = records.subList(sweep + 1, records.size()).stream().allMatch(HarvestRecord.Progress.class::isInstance);
    if (!last) {
      return "sweep must be the last record apart from progress";
    }
    Set<String> missing = new LinkedHashSet<>(items.keySet());
    missing.removeAll(reportedIds(records));
    return missing.isEmpty() ? null : "swept without reporting live items, so they would be deleted: " + missing;
  }

  private void checkLimit(Map<String, HarvestRecord.Item> items) {
    String problem = limitProblem(items);
    record("limit-respected", problem == null, problem);
  }

  private String limitProblem(Map<String, HarvestRecord.Item> items) {
    HarvestRequest.Limits oneItem = new HarvestRequest.Limits(1, null);
    Outcome limited = harvest(null, oneItem);
    String problem = limitedRunProblem(limited, items);
    if (problem != null) {
      return problem;
    }

    Outcome byChars = harvest(null, new HarvestRequest.Limits(null, 1L));
    if (!byChars.completed()) {
      return "run limited by characters: " + byChars.describe();
    }
    int allowed = itemsAllowedByChars(items, 1L);
    int emitted = items(byChars.records()).size();
    if (emitted > allowed) {
      return "emitted " + emitted + " items with maxCharsPerRun = 1, where " + allowed + " fit";
    }

    return target.manifest().has(Capability.INCREMENTAL) ? limitedProgressProblem(limited, items, oneItem) : null;
  }

  private static String limitedRunProblem(Outcome limited, Map<String, HarvestRecord.Item> items) {
    if (!limited.completed()) {
      return "limited run: " + limited.describe();
    }
    int emitted = items(limited.records()).size();
    if (emitted > 1) {
      return "emitted " + emitted + " items with maxItemsPerRun = 1";
    }
    if (limited.records().stream().anyMatch(HarvestRecord.Sweep.class::isInstance)
      && !reportedIds(limited.records()).containsAll(items.keySet())) {
      return "emitted sweep from a run cut short by a limit";
    }
    return null;
  }

  /** A run stopped by a limit must leave a cursor that lets the next run continue. */
  private String limitedProgressProblem(Outcome limited, Map<String, HarvestRecord.Item> items, HarvestRequest.Limits limit) {
    if (items.isEmpty()) {
      return null;
    }
    Set<String> covered = new LinkedHashSet<>(items(limited.records()).keySet());
    if (covered.isEmpty()) {
      return "a limited run emitted no items, so the source would never advance";
    }
    JsonNode cursor = lastCursor(limited.records());
    for (int step = 0; step < MAX_LIMITED_STEPS && !covered.containsAll(items.keySet()); step++) {
      if (cursor == null) {
        return "a limited run left no checkpoint, so the next run can't continue";
      }
      Outcome next = harvest(cursor, limit);
      String problem = limitedRunProblem(next, items);
      if (problem != null) {
        return problem;
      }
      if (!covered.addAll(items(next.records()).keySet())) {
        return "a limited run from the last checkpoint added nothing new, so the source stalls";
      }
      cursor = lastCursor(next.records());
    }
    return null;
  }

  private static int itemsAllowedByChars(Map<String, HarvestRecord.Item> items, long maxChars) {
    int allowed = 0;
    long chars = 0;
    for (HarvestRecord.Item item : items.values()) {
      long size = chars(item);
      if (allowed > 0 && chars + size > maxChars) {
        break;
      }
      chars += size;
      allowed++;
    }
    return allowed;
  }

  private static long chars(HarvestRecord.Item item) {
    return switch (item) {
      case HarvestRecord.Document document -> document.text().length();
      case HarvestRecord.ConversationThread thread -> thread.turns().stream().mapToLong(turn -> turn.text().length()).sum();
    };
  }

  private void checkStdoutClose() {
    String check = "exits-when-stdout-closes";
    HarvestRequest request = request(options.config(), null, HarvestRequest.Limits.NONE);
    switch (ProcessRun.exitsWhenStdoutCloses(target, request, options.timeout(), CLOSE_GRACE)) {
      case EXITED -> record(check, true, "");
      case ALREADY_FINISHED -> skip(check, "the harvester finished before stdout closed; use a stub that outlasts its first record");
      case STILL_RUNNING -> record(check, false, "still running " + CLOSE_GRACE.toSeconds() + " seconds after stdout closed");
      case NO_OUTPUT -> record(check, false, "the harvester produced no output before the timeout");
    }
  }

  private static JsonNode lastCursor(List<HarvestRecord> records) {
    JsonNode cursor = null;
    for (HarvestRecord record : records) {
      if (record instanceof HarvestRecord.Checkpoint checkpoint) {
        cursor = checkpoint.cursor();
      }
    }
    return cursor;
  }

  private static Map<String, HarvestRecord.Item> items(List<HarvestRecord> records) {
    Map<String, HarvestRecord.Item> items = new LinkedHashMap<>();
    for (HarvestRecord record : records) {
      if (record instanceof HarvestRecord.Item item) {
        items.put(item.id(), item);
      }
    }
    return items;
  }

  private static Set<String> reportedIds(List<HarvestRecord> records) {
    Set<String> ids = new LinkedHashSet<>(items(records).keySet());
    for (HarvestRecord record : records) {
      if (record instanceof HarvestRecord.Seen seen) {
        ids.addAll(seen.ids());
      }
    }
    return ids;
  }
}
