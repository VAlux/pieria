package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Code narrative summarization settings (see {@code CodeSummarizationService}): after the
 * deterministic code index, the synthesis (large) model can write interpretive summary memories —
 * one repo architecture overview, per-module summaries, and optionally per-file summaries.
 *
 * <p>Opt-in ({@code enabled=false}) because it puts the large model in the indexing path; the
 * per-request {@code summarize} flag on {@code POST /code/async} (CLI {@code --summarize}) can
 * force it on/off per run regardless of this default. Process-global on purpose — deliberately not
 * part of {@code DaemonOverrides}; add it there only if per-profile pushed config is ever needed.
 *
 * @param enabled                        master switch (default off; model cost)
 * @param granularity                    cumulative depth: {@code architecture} (overview only),
 *                                       {@code module} (modules + overview, the default), or
 *                                       {@code file} (adds per-file summaries)
 * @param maxSourceCharsPerFile          source-text cap in the per-file prompt
 * @param maxFilesPerModulePrompt        member-outline cap in the per-module prompt
 * @param maxModulesInArchitecturePrompt module cap in the architecture prompt
 */
@ConfigurationProperties(prefix = "pieria.code.summarization")
public record CodeSummarizationProperties(
  @DefaultValue("false") boolean enabled,
  @DefaultValue("module") String granularity,
  @DefaultValue("20000") int maxSourceCharsPerFile,
  @DefaultValue("80") int maxFilesPerModulePrompt,
  @DefaultValue("40") int maxModulesInArchitecturePrompt) {

  /** Whether per-file summaries are generated. */
  public boolean fileLevel() {
    return "file".equalsIgnoreCase(granularity);
  }

  /** Whether per-module summaries are generated (implied by file level). */
  public boolean moduleLevel() {
    return fileLevel() || "module".equalsIgnoreCase(granularity);
  }
}
