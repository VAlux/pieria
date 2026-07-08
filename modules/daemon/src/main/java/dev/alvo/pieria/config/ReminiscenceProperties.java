package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Orphan-adoption ("reminiscence") tuning (see {@code ReminiscenceService}): the background task
 * that finds edgeless memories and retroactively runs the ingest graph-extraction over their
 * content, weaving them into the entity-relation graph.
 *
 * <p>Graph extraction is batched to honor the single-GPU-Ollama lesson — one {@code extractGraphAll}
 * call per sub-batch rather than one per memory. A sub-batch is closed when it reaches
 * {@code batchSize} memories <em>or</em> {@code batchCharBudget} cumulative content characters
 * (whichever first, always at least one memory), so one long memory becomes its own single-item call
 * and the batched prompt stays inside the model context window. {@code scanPageSize} bounds how many
 * orphans are fetched from the store per page.
 *
 * <p>Process-global on purpose — deliberately not part of {@code DaemonOverrides}; add it there only
 * if per-profile pushed config is ever needed.
 *
 * @param batchSize       memories per {@code extractGraphAll} model call (before the char budget caps it)
 * @param batchCharBudget max cumulative content characters per model call
 * @param scanPageSize    orphans fetched from the store per page (should be &ge; batchSize)
 */
@ConfigurationProperties(prefix = "pieria.reminiscence")
public record ReminiscenceProperties(
  @DefaultValue("8") int batchSize,
  @DefaultValue("6000") int batchCharBudget,
  @DefaultValue("500") int scanPageSize) {
}
