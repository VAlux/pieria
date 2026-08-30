package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Execution-trace ingestion tuning: capture limits, spool bounds, noise rejection, and how much
 * model work a batch may do.
 *
 * <p>A standalone properties record rather than a component of {@code PieriaProperties.Ingestion},
 * which is constructed positionally in sixteen test files. This follows the same pattern as
 * {@link AuditProperties} and {@link ReminiscenceProperties} and keeps the property prefix the
 * design specifies.
 *
 * @param enabled                  master switch; when false the daemon accepts and discards traces,
 *                                 so the feature is off without uninstalling the hook
 * @param maxOutputChars           per-field truncation budget for {@code output}/{@code error}
 * @param spoolMaxBytes            spool file size above which the oldest half is dropped
 * @param spoolRetentionDays       age above which an abandoned spool file is swept
 * @param stopDrainThresholdBytes  spool size at which an end-of-turn Stop hook drains
 * @param stopDrainThresholdEvents spool event count at which an end-of-turn Stop hook drains
 * @param toolDenylist             tools whose <em>successful</em> calls carry no durable signal
 * @param skipUnchangedOutcomes    drop a trace whose active outcome already records the same status
 *                                 and error digest
 * @param recipeExtractionEnabled  whether the small model derives procedural instructions
 * @param maxRecipesPerBatch       cap on instructions derived from one ingest
 * @param maxLinkedSymbols         cap on code-index symbols linked into one trace memory
 * @param recallBoost              post-fusion multiplier for trace-sourced candidates
 *                                 ({@code 1.0} = off)
 */
@ConfigurationProperties(prefix = "pieria.ingestion.trace")
public record TraceProperties(
  @DefaultValue("true") boolean enabled,
  @DefaultValue("4000") int maxOutputChars,
  @DefaultValue("4194304") long spoolMaxBytes,
  @DefaultValue("7") int spoolRetentionDays,
  @DefaultValue("65536") long stopDrainThresholdBytes,
  @DefaultValue("50") int stopDrainThresholdEvents,
  @DefaultValue("Read,Grep,Glob,LS,TodoWrite,NotebookRead,WebSearch,WebFetch,Task")
  List<String> toolDenylist,
  @DefaultValue("true") boolean skipUnchangedOutcomes,
  @DefaultValue("true") boolean recipeExtractionEnabled,
  @DefaultValue("3") int maxRecipesPerBatch,
  @DefaultValue("10") int maxLinkedSymbols,
  @DefaultValue("1.0") double recallBoost) {

  public TraceProperties {
    maxOutputChars = Math.max(200, maxOutputChars);
    spoolMaxBytes = Math.max(4096L, spoolMaxBytes);
    spoolRetentionDays = Math.max(1, spoolRetentionDays);
    stopDrainThresholdBytes = Math.max(0L, stopDrainThresholdBytes);
    stopDrainThresholdEvents = Math.max(0, stopDrainThresholdEvents);
    toolDenylist = toolDenylist == null ? List.of() : List.copyOf(toolDenylist);
    maxRecipesPerBatch = Math.max(0, maxRecipesPerBatch);
    maxLinkedSymbols = Math.max(0, maxLinkedSymbols);
    recallBoost = Math.clamp(recallBoost, 0.0, 10.0);
  }

  /** Defaults, for call sites that construct this directly (tests, and the CLI hook's fallbacks). */
  public static TraceProperties defaults() {
    return new TraceProperties(true, 4000, 4_194_304L, 7, 65_536L, 50,
      List.of("Read", "Grep", "Glob", "LS", "TodoWrite", "NotebookRead", "WebSearch", "WebFetch",
        "Task"),
      true, true, 3, 10, 1.0);
  }
}
