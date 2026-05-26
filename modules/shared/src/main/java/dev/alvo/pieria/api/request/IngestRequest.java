package dev.alvo.pieria.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Body of POST /v1/profiles/{name}/ingest.
 */
public record IngestRequest(
  @NotBlank String sessionId,
  @NotEmpty @Valid List<MessageDto> messages) {

  public record MessageDto(
    @NotBlank String role,
    @NotBlank String content) {
  }
}
