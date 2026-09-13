package dev.alvo.pieria.api.response;

import java.util.List;

/**
 * One line of an NDJSON profile export. {@code createdAt} is pre-stringified (not an
 * {@code Instant}) so export serialization does not depend on a jsr310 module being registered
 * on the injected {@code ObjectMapper}.
 */
public record ExportLineResponse(String profileName, ExportMemory memory, List<RevisionResponse> revisions, List<TemporalValidity> validityHistory) {
  public ExportLineResponse(String profileName, ExportMemory memory) { this(profileName, memory, List.of(), List.of()); }
  public ExportLineResponse withRevisions(List<RevisionResponse> history) { return new ExportLineResponse(profileName, memory, history, validityHistory); }

  public ExportLineResponse withValidityHistory(List<TemporalValidity> segments) {
    return new ExportLineResponse(profileName, memory, revisions, segments);
  }

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
