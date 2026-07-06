package dev.alvo.pieria.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Body of POST /v1/profiles/{name}/ingest.
 *
 * <p>{@code extractionSamples} optionally overrides how many independent extract passes the daemon
 * runs per chunk for this one request (null ⇒ use the profile's configured default of 1). Bulk
 * seeds like {@code pieria onboard} raise it to saturate extraction in a single run without making
 * every background ingest pay the multiplied model cost.
 */
public record IngestRequest(
  @NotBlank String sessionId,
  @NotEmpty @Valid List<MessageDto> messages,
  @Positive Integer extractionSamples) {

  /** Convenience for callers that don't override sampling. */
  public IngestRequest(String sessionId, List<MessageDto> messages) {
    this(sessionId, messages, null);
  }

  public record MessageDto(
    @NotBlank String role,
    @NotBlank String content) {
  }
}
