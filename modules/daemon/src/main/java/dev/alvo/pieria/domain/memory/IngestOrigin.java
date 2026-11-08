package dev.alvo.pieria.domain.memory;

import dev.alvo.pieria.api.response.MemoryOrigin;

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
}
