package dev.alvo.pieria.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /v1/profiles/{name}/memories (explicit single-memory write).
 */
public record RememberRequest(
  @NotBlank String type,
  @NotBlank String content,
  String sessionId,
  String topicKey,
  String payload,
  String validFrom,
  String validTo,
  Boolean checkConflicts) {
  public RememberRequest(String type, String content, String sessionId, String topicKey, String payload) {
    this(type, content, sessionId, topicKey, payload, null, null, false);
  }
}
