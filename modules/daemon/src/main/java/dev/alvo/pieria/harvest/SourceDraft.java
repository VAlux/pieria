package dev.alvo.pieria.harvest;

import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/** A new source. {@code start} makes it active at once instead of waiting to be resumed. */
public record SourceDraft(String id, String harvesterId, ObjectNode config, Map<String, String> secrets, SourceMode mode,
                          long intervalSeconds, Integer maxItemsPerRun, Long maxCharsPerRun, boolean start) {
}
