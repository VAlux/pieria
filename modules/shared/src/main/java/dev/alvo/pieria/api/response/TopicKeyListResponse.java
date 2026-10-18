package dev.alvo.pieria.api.response;

import java.time.Instant;
import java.util.List;

/**
 * GET /v1/profiles/{name}/topic-keys: the memory visible now under each matching (type, topic key),
 * which a new memory of that type and key would replace.
 */
public record TopicKeyListResponse(List<TopicKey> keys) {

  public record TopicKey(String topicKey, String type, String memoryId, String content, Instant updatedAt) {
  }
}
