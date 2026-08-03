package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.api.response.OnboardResult;
import dev.alvo.pieria.code.CodeIndexingService;
import dev.alvo.pieria.code.CodeIndexingService.CodeIndexSummary;
import dev.alvo.pieria.code.CodeIndexingService.SourceFile;
import dev.alvo.pieria.code.CodeSummarizationService;
import dev.alvo.pieria.code.CodeSummarizationService.SummarizationResult;
import dev.alvo.pieria.config.CodeSummarizationProperties;
import dev.alvo.pieria.config.model.DiscoveryConfig;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.task.TaskCancelledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Onboarding source that builds the source-code intelligence index from a project's tracked source
 * files. Unlike the content sources, this targets the code-index pipeline (symbols, edges, optional
 * narrative summaries) directly rather than memory extraction, so it does not use
 * {@link ContentIngestor}. The optional summarization pass is best-effort: its failure never affects
 * the index result.
 */
@Component
public class SourceCodeOnboardingSource implements OnboardingSource<SourceSpec.SourceCode> {

  private static final Logger log = LoggerFactory.getLogger(SourceCodeOnboardingSource.class);

  private final CodeIndexingService indexing;
  private final CodeSummarizationService summarization;
  private final CodeSummarizationProperties summarizationProperties;

  public SourceCodeOnboardingSource(CodeIndexingService indexing,
                                    CodeSummarizationService summarization,
                                    CodeSummarizationProperties summarizationProperties) {
    this.indexing = indexing;
    this.summarization = summarization;
    this.summarizationProperties = summarizationProperties;
  }

  @Override
  public Class<SourceSpec.SourceCode> specType() {
    return SourceSpec.SourceCode.class;
  }

  @Override
  public OnboardingWork begin(String profile, SourceSpec.SourceCode spec, IngestProgressListener progress) {
    Path root = Roots.require(spec.root());
    DiscoveryConfig discovery = spec.discovery() != null ? spec.discovery() : DiscoveryConfig.defaults();
    List<SourceFile> files = CodeDiscovery.create(root, discovery).discover();

    CodeIndexSummary summary = indexing.index(profile, null, files, spec.reindex(), progress);

    boolean summarize = spec.summarize() != null ? spec.summarize() : summarizationProperties.enabled();
    return finishProgress -> {
      SummarizationResult summaries = SummarizationResult.empty();
      if (summarize) {
        try {
          summaries = summarization.summarize(profile, files, finishProgress);
        } catch (TaskCancelledException e) {
          throw e;
        } catch (RuntimeException e) {
          log.warn("onboard source-code: summarization failed ({}); index result unaffected", e.toString());
        }
      }
      return OnboardResult.code(
        summary.filesReceived(),
        summary.memoriesStored(),
        summary.symbols(),
        summary.resolvedEdges() + summary.heuristicEdges(),
        summaries.stored());
    };
  }
}
