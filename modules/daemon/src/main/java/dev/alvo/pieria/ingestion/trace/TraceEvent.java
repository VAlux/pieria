package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.tools.Redaction;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A redacted, bounded, time-resolved tool call: the daemon's own view of an inbound
 * {@link TraceEventDto}.
 *
 * <p>It exists rather than reusing the DTO because three real transformations happen on the way in:
 * secrets and machine paths are scrubbed, oversized output is capped, and the event time is
 * resolved through an explicit fallback chain. Downstream stages never see the raw DTO.
 *
 * @param occurredAtFromReceipt whether {@code occurredAt} fell all the way through to the daemon's
 *                              clock, which the service counts and logs. A trace with no timestamps
 *                              must not be silently indistinguishable from one that genuinely ran
 *                              at ingest time.
 */
public record TraceEvent(
  String id,
  String sessionId,
  String tool,
  String args,
  String output,
  TraceStatus status,
  Integer exitCode,
  String error,
  Instant occurredAt,
  boolean occurredAtFromReceipt,
  int redactionHits) {

  private static final String NO_OUTPUT = "no output captured";

  /**
   * Build the domain event: scrub every free-text field, resolve the event time
   * ({@code endedAt} then {@code startedAt} then {@code receiptTime}), then content-address it.
   */
  public static TraceEvent from(String profileId,
                                String sessionId,
                                TraceEventDto dto,
                                int budget,
                                Path repoRoot,
                                Path userHome,
                                Instant receiptTime) {
    Redaction.Redacted args = Redaction.scrub(dto.args(), budget, repoRoot, userHome);
    Redaction.Redacted output = Redaction.scrub(dto.output(), budget, repoRoot, userHome);
    Redaction.Redacted error = Redaction.scrub(dto.error(), budget, repoRoot, userHome);

    boolean fromReceipt = dto.endedAt() == null && dto.startedAt() == null;
    Instant occurredAt = dto.endedAt() != null ? dto.endedAt()
      : dto.startedAt() != null ? dto.startedAt()
      : receiptTime;

    String id = ContentId.forTrace(
      profileId, sessionId, dto.tool(), args.text(), dto.status(), occurredAt);

    return new TraceEvent(
      id,
      sessionId,
      dto.tool(),
      args.text(),
      output.text(),
      dto.status(),
      dto.exitCode(),
      error.text(),
      occurredAt,
      fromReceipt,
      args.hits() + output.hits() + error.hits());
  }

  /**
   * How the call reads in prose. {@code Bash} args already name the program; every other tool needs
   * its own name to be legible.
   */
  public String invocation() {
    String trimmed = args == null ? "" : args.strip();
    if (trimmed.isEmpty()) {
      return tool;
    }
    return "Bash".equals(tool) ? trimmed : tool + " " + trimmed;
  }

  /** The grouping key both trace topic keys derive from. */
  public String signature() {
    return CommandSignature.of(tool, args);
  }

  /**
   * The one line worth quoting in a memory: the first non-blank line of {@code error}, else the
   * last non-blank line of {@code output}, else a placeholder. Error beats output because a build
   * tool's stderr names the failure while its stdout narrates progress.
   */
  public String signalLine() {
    String firstError = firstNonBlankLine(error);
    if (firstError != null) {
      return firstError;
    }
    String lastOutput = lastNonBlankLine(output);
    return lastOutput != null ? lastOutput : NO_OUTPUT;
  }

  /**
   * The text an error digest is taken from: stderr when present, else whatever stdout carried.
   * Shared by {@code TraceMemoryFactory.outcome} (what {@code error_digest} is computed from) and
   * {@code TraceRelevanceFilter.isUnchanged} (what an incoming trace's digest is compared against) —
   * both need the same input for "is this outcome unchanged" and "what gets stored" to stay in sync.
   */
  public String errorOrOutput() {
    return error != null && !error.isBlank() ? error : output;
  }

  private static String firstNonBlankLine(String text) {
    if (text == null) {
      return null;
    }
    for (String line : text.split("\n")) {
      String stripped = line.strip();
      if (!stripped.isEmpty()) {
        return stripped;
      }
    }
    return null;
  }

  private static String lastNonBlankLine(String text) {
    if (text == null) {
      return null;
    }
    String[] lines = text.split("\n");
    for (int i = lines.length - 1; i >= 0; i--) {
      String stripped = lines[i].strip();
      if (!stripped.isEmpty()) {
        return stripped;
      }
    }
    return null;
  }
}
