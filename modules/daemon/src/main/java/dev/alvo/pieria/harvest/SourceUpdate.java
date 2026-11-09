package dev.alvo.pieria.harvest;

import tools.jackson.databind.node.ObjectNode;

/** Changes to a source. A null field keeps its current value; config keys are merged over the existing ones. */
public record SourceUpdate(ObjectNode config, SourceMode mode, Long intervalSeconds, Integer maxItemsPerRun, Long maxCharsPerRun,
                           boolean resetCursor) {
}
