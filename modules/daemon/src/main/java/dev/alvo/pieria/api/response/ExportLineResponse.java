package dev.alvo.pieria.api.response;

/**
 * One line of an NDJSON profile export. {@code createdAt} is pre-stringified (not an
 * {@code Instant}) so export serialization does not depend on a jsr310 module being registered
 * on the injected {@code ObjectMapper}.
 */
public record ExportLineResponse(String profileName, ExportMemory memory) {

  public record ExportMemory(
    String id,
    String type,
    String content,
    String topicKey,
    String sessionId,
    boolean superseded,
    String payload,
    String createdAt) {
  }
}
