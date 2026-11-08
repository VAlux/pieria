package dev.alvo.pieria.harvester.conformance;

import dev.alvo.pieria.harvester.conformance.ConformanceReport.Result;
import dev.alvo.pieria.harvester.conformance.ConformanceReport.Status;
import dev.alvo.pieria.harvester.conformance.ProcessRun.Outcome;
import dev.alvo.pieria.harvester.manifest.Capability;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
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
 * harvester written in any language.
 */
public final class ConformanceKit {

  private static final Duration CLOSE_GRACE = Duration.ofSeconds(10);
  private static final String NOT_COMPLETED = "harvest did not complete";
  private static final List<String> OUTPUT_DEPENDENT_CHECKS = List.of(
    "harvest-emits-items", "capabilities-declared", "ids-stable-across-runs", "resume-loses-nothing",
    "sweep-is-complete", "limit-respected", "exits-when-stdout-closes");

  private final ConformanceTarget target;
  private final ConformanceOptions options;
  private final List<Result> results = new ArrayList<>();

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

  private Outcome harvest(JsonNode cursor, HarvestRequest.Limits limits) {
    return ProcessRun.execute(target, "harvest", request(options.config(), cursor, limits), options.timeout());
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
    if (!full.completed()) {
      OUTPUT_DEPENDENT_CHECKS.forEach(check -> record(check, false, NOT_COMPLETED));
      return;
    }

    Map<String, HarvestRecord.Item> items = items(full.records());
    record("harvest-emits-items", !items.isEmpty(), "no documents or threads were emitted");
    checkCapabilities(full);
    checkStableIds(items);
    Outcome resumed = checkResume(full);
    checkSweeps(items, full, resumed);
    checkLimit(items);
    record("exits-when-stdout-closes",
      ProcessRun.exitsWhenStdoutCloses(target, request(options.config(), null, HarvestRequest.Limits.NONE), options.timeout(), CLOSE_GRACE),
      "still running " + CLOSE_GRACE.toSeconds() + " seconds after stdout closed");
  }

  private void checkValidConfig() {
    Outcome outcome = ProcessRun.execute(target, "check", request(options.config(), null, HarvestRequest.Limits.NONE), options.timeout());
    record("check-accepts-valid-config", outcome.completed(), outcome.describe());
  }

  private void checkInvalidConfig() {
    String check = "check-rejects-invalid-config";
    if (options.invalidConfig() == null) {
      skip(check, "no invalid config was supplied");
      return;
    }
    Outcome outcome = ProcessRun.execute(target, "check", request(options.invalidConfig(), null, HarvestRequest.Limits.NONE), options.timeout());
    HarvestRecord.Failure failure = outcome.failure();
    boolean rejected = !outcome.timedOut() && outcome.exitCode() != 0 && failure != null
      && (failure.errorClass() == ErrorClass.AUTH || failure.errorClass() == ErrorClass.CONFIG);
    record(check, rejected, "expected an auth or config error, got: " + outcome.describe());
  }

  private void checkCapabilities(Outcome full) {
    boolean deletes = full.records().stream()
      .anyMatch(r -> r instanceof HarvestRecord.Tombstone || r instanceof HarvestRecord.Sweep);
    boolean checkpoints = full.records().stream().anyMatch(HarvestRecord.Checkpoint.class::isInstance);
    if (deletes && !target.manifest().has(Capability.DELETES)) {
      record("capabilities-declared", false, "emitted tombstone or sweep without the deletes capability");
    } else if (target.manifest().has(Capability.INCREMENTAL) && !checkpoints) {
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

  /** Returns the resumed run for the sweep check, or null when the resume check did not run. */
  private Outcome checkResume(Outcome full) {
    String check = "resume-loses-nothing";
    if (!target.manifest().has(Capability.INCREMENTAL)) {
      skip(check, "the incremental capability is not declared");
      return null;
    }
    List<Integer> candidates = new ArrayList<>();
    List<HarvestRecord> records = full.records();
    for (int i = 0; i < records.size(); i++) {
      if (records.get(i) instanceof HarvestRecord.Checkpoint && !items(records.subList(i + 1, records.size())).isEmpty()) {
        candidates.add(i);
      }
    }
    if (candidates.isEmpty()) {
      skip(check, "no checkpoint has items after it");
      return null;
    }
    int checkpoint = candidates.get(candidates.size() / 2);
    Outcome resumed = harvest(((HarvestRecord.Checkpoint) records.get(checkpoint)).cursor(), HarvestRequest.Limits.NONE);
    if (!resumed.completed()) {
      record(check, false, "resumed run: " + resumed.describe());
      return null;
    }
    Set<String> missing = new LinkedHashSet<>(items(records.subList(checkpoint + 1, records.size())).keySet());
    missing.removeAll(items(resumed.records()).keySet());
    record(check, missing.isEmpty(), "items after the checkpoint were lost on resume: " + missing);
    return resumed;
  }

  private void checkSweeps(Map<String, HarvestRecord.Item> items, Outcome full, Outcome resumed) {
    List<Outcome> runs = resumed == null ? List.of(full) : List.of(full, resumed);
    for (Outcome run : runs) {
      if (run.records().stream().anyMatch(HarvestRecord.Sweep.class::isInstance)) {
        Set<String> missing = new LinkedHashSet<>(items.keySet());
        missing.removeAll(reportedIds(run.records()));
        if (!missing.isEmpty()) {
          record("sweep-is-complete", false, "swept without reporting live items, so they would be deleted: " + missing);
          return;
        }
      }
    }
    record("sweep-is-complete", true, "");
  }

  private void checkLimit(Map<String, HarvestRecord.Item> items) {
    Outcome limited = harvest(null, new HarvestRequest.Limits(1, null));
    if (!limited.completed()) {
      record("limit-respected", false, "limited run: " + limited.describe());
      return;
    }
    int emitted = items(limited.records()).size();
    boolean swept = limited.records().stream().anyMatch(HarvestRecord.Sweep.class::isInstance);
    if (emitted > 1) {
      record("limit-respected", false, "emitted " + emitted + " items with maxItemsPerRun = 1");
    } else if (swept && items.size() > 1) {
      record("limit-respected", false, "emitted sweep from a run cut short by a limit");
    } else {
      record("limit-respected", true, "");
    }
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
