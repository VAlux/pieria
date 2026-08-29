package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.domain.memory.Memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic noise rejection ahead of memory derivation. Every rule is a plain predicate: no
 * model is consulted about whether a trace is interesting, because the judgment ("did this fail",
 * "have we already recorded this outcome") is mechanical.
 *
 * <p>Rules, in order, first match wins:
 * <ol>
 *   <li><b>keep</b> any failure, whatever the tool — a failing read is signal;</li>
 *   <li><b>drop</b> a non-failing call to a denylisted read-only tool;</li>
 *   <li><b>drop</b> all but the last occurrence of a {@code (signature, status)} in the batch;</li>
 *   <li><b>drop</b> a trace whose active outcome already records the same status and error
 *       digest.</li>
 * </ol>
 */
public class TraceRelevanceFilter {

  /** Separator for the in-batch dedupe key. Both halves are slug/enum text, so this is unambiguous. */
  private static final String KEY_SEPARATOR = "|";

  /** Tools whose calls always carry signal, regardless of the denylist. */
  private static final Set<String> ALWAYS_KEPT =
    Set.of("bash", "edit", "write", "multiedit", "notebookedit");

  private static final Pattern STATUS_FIELD = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern DIGEST_FIELD =
    Pattern.compile("\"error_digest\"\\s*:\\s*\"([^\"]*)\"");

  private final TraceProperties properties;
  private final Set<String> denylist;

  public TraceRelevanceFilter(TraceProperties properties) {
    this.properties = properties;
    this.denylist = new LinkedHashSet<>(
      properties.toolDenylist().stream().map(tool -> tool.toLowerCase(Locale.ROOT)).toList());
  }

  /** Survivors plus a per-rule tally of what was dropped, for the ingest log. */
  public record Result(List<TraceEvent> kept, Map<String, Integer> droppedByRule) {
  }

  /**
   * @param events              the batch, in arrival order
   * @param activeOutcomeLookup signature to the active {@code trace:outcome:<signature>} memory, if
   *                            any; supplied by the service, which owns the store
   */
  public Result filter(List<TraceEvent> events,
                       Function<String, Optional<Memory>> activeOutcomeLookup) {
    if (events == null || events.isEmpty()) {
      return new Result(List.of(), Map.of());
    }
    Map<String, Integer> dropped = new LinkedHashMap<>();

    // Rules 1-2: per-event admission.
    List<TraceEvent> admitted = new ArrayList<>();
    for (TraceEvent event : events) {
      if (event.status() == TraceStatus.FAILURE || isAlwaysKept(event) || !isDenylisted(event)) {
        admitted.add(event);
      } else {
        dropped.merge("denylisted-tool", 1, Integer::sum);
      }
    }

    // Rule 3: keep only the last occurrence of each (signature, status) in the batch, preserving
    // arrival order. Track the index of each (signature, status) key's last occurrence, then walk
    // the admitted list in order and keep only matching events.
    Map<String, Integer> lastIndexPerKey = new LinkedHashMap<>();
    for (int i = 0; i < admitted.size(); i++) {
      TraceEvent event = admitted.get(i);
      lastIndexPerKey.put(event.signature() + KEY_SEPARATOR + event.status().wire(), i);
    }
    int collapsed = admitted.size() - lastIndexPerKey.size();
    if (collapsed > 0) {
      dropped.merge("in-batch-repeat", collapsed, Integer::sum);
    }
    List<TraceEvent> deduped = new ArrayList<>();
    for (int i = 0; i < admitted.size(); i++) {
      TraceEvent event = admitted.get(i);
      String key = event.signature() + KEY_SEPARATOR + event.status().wire();
      if (lastIndexPerKey.get(key) == i) {
        deduped.add(event);
      }
    }

    // Rule 4: skip an outcome the store already records unchanged.
    List<TraceEvent> kept = new ArrayList<>();
    for (TraceEvent event : deduped) {
      if (properties.skipUnchangedOutcomes() && isUnchanged(event, activeOutcomeLookup)) {
        dropped.merge("unchanged-outcome", 1, Integer::sum);
      } else {
        kept.add(event);
      }
    }
    return new Result(List.copyOf(kept), Map.copyOf(dropped));
  }

  private boolean isAlwaysKept(TraceEvent event) {
    return event.tool() != null && ALWAYS_KEPT.contains(event.tool().toLowerCase(Locale.ROOT));
  }

  private boolean isDenylisted(TraceEvent event) {
    return event.tool() != null && denylist.contains(event.tool().toLowerCase(Locale.ROOT));
  }

  /**
   * Whether the active outcome for this signature already says the same thing. Compares status and
   * the error digest rather than raw text, so a recompile that shifts a stack frame does not read
   * as a new outcome. An active memory carrying neither field is treated as changed: it may be
   * hand-written or predate the digest, and swallowing a real result would be worse than a
   * redundant write.
   */
  private boolean isUnchanged(TraceEvent event,
                              Function<String, Optional<Memory>> activeOutcomeLookup) {
    Optional<Memory> active = activeOutcomeLookup.apply(event.signature());
    if (active.isEmpty()) {
      return false;
    }
    String payload = active.get().payload();
    String storedStatus = field(STATUS_FIELD, payload);
    String storedDigest = field(DIGEST_FIELD, payload);
    if (storedStatus == null || storedDigest == null) {
      return false;
    }
    String incomingDigest = CommandSignature.errorDigest(event.errorOrOutput());
    return storedStatus.equals(event.status().wire()) && storedDigest.equals(incomingDigest);
  }

  private static String field(Pattern pattern, String payload) {
    if (payload == null) {
      return null;
    }
    Matcher matcher = pattern.matcher(payload);
    return matcher.find() ? matcher.group(1) : null;
  }
}
