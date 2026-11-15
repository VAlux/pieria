package dev.alvo.pieria.api.request;

import tools.jackson.databind.node.ObjectNode;

/**
 * Body of PATCH /v1/profiles/{name}/sources/{id}. Null fields keep their current values; within
 * {@code config}, a key set to null is removed, so the harvester's default applies again.
 */
public record UpdateSourceRequest(ObjectNode config, String mode, Long intervalSeconds, Integer maxItemsPerRun,
                                  Long maxCharsPerRun, Boolean resetCursor) {
}
