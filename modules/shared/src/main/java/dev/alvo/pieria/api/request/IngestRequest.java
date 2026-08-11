package dev.alvo.pieria.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/**
 * Body of POST /v1/profiles/{name}/ingest.
 *
 * <p>{@code extractionSamples} optionally overrides how many independent extract passes the daemon
 * runs per chunk for this one request (null ⇒ use the profile's configured default of 1). Bulk
 * seeds like {@code pieria onboard} raise it to saturate extraction in a single run without making
 * every background ingest pay the multiplied model cost.
 *
 * <p>{@code occurredAt} is <em>when the conversation happened</em>, which is not always when it is
 * ingested. Relative dates in the transcript ("yesterday", "today", "tomorrow") are resolved against
 * it, so a transcript captured live can be replayed or back-filled later without its dates drifting
 * to the ingest wall clock. {@code null} ⇒ the daemon uses its own clock, which is right for the
 * common case of ingesting a conversation as it happens.
 */
public record IngestRequest(
  @NotBlank String sessionId,
  @NotEmpty @Valid List<MessageDto> messages,
  @Positive Integer extractionSamples,
  Instant occurredAt) {

  /** Convenience for callers that don't override sampling or supply an occurrence time. */
  public IngestRequest(String sessionId, List<MessageDto> messages) {
    this(sessionId, messages, null, null);
  }

  /** Convenience for callers that override sampling but not the occurrence time. */
  public IngestRequest(String sessionId, List<MessageDto> messages, Integer extractionSamples) {
    this(sessionId, messages, extractionSamples, null);
  }

  /**
   * One inbound message. {@code timestamp} is when <em>this turn</em> was spoken and takes precedence
   * over the request's {@code occurredAt}; it is the right field for a multi-session transcript whose
   * turns span months. {@code null} ⇒ fall back to {@code occurredAt}, then the daemon's clock.
   */
  public record MessageDto(
    @NotBlank String role,
    @NotBlank String content,
    Instant timestamp) {

    /** Convenience for callers with no per-turn timestamp. */
    public MessageDto(String role, String content) {
      this(role, content, null);
    }
  }
}
