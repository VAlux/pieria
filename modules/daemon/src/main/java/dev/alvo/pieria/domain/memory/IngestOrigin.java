package dev.alvo.pieria.domain.memory;

import dev.alvo.pieria.api.response.MemoryOrigin;
import dev.alvo.pieria.ingestion.model.Classification;

/**
 * Identifies external-source content entering the ingest pipeline. A null origin means the content
 * is the user's own conversation.
 *
 * @param sourceId   the configured source's id; matches {@link MemoryOrigin#SOURCE_ID_REGEX}
 * @param harvester  the harvester type, e.g. {@code "jira"}
 * @param externalId the item's id at the source
 * @param url        link to the item; may be null
 */
public record IngestOrigin(String sourceId, String harvester, String externalId, String url) {

  public IngestOrigin {
    if (sourceId == null || !sourceId.matches(MemoryOrigin.SOURCE_ID_REGEX)) {
      throw new IllegalArgumentException("invalid source id: " + sourceId);
    }
    if (harvester == null || harvester.isBlank()) {
      throw new IllegalArgumentException("harvester must not be blank");
    }
    if (externalId == null || externalId.isBlank()) {
      throw new IllegalArgumentException("externalId must not be blank");
    }
  }

  /**
   * Applies the limits on harvested content. Only {@code fact} and {@code event} are allowed, because
   * a web page or ticket must never become a standing instruction or an item on the user's to-do
   * list. Topic keys are namespaced to this source so a keyed fact supersedes only within it.
   */
  public Classification govern(Classification classification) {
    MemoryType type = classification.type() == MemoryType.EVENT ? MemoryType.EVENT : MemoryType.FACT;
    String key = classification.topicKey();
    String namespaced = key == null || key.isBlank() ? key : "source:" + sourceId + ":" + key;
    return new Classification(type, namespaced, classification.interrogativeQueries(), classification.payload());
  }
}
