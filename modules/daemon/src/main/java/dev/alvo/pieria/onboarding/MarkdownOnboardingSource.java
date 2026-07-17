package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.onboarding.MarkdownDiscovery.Doc;
import dev.alvo.pieria.tools.io.FileOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Onboarding source that seeds a profile from a project's markdown documentation. Discovers
 * {@code *.md} under the spec's {@code root} and feeds each doc through the memory-extraction
 * pipeline via {@link ContentIngestor}.
 */
@Component
public class MarkdownOnboardingSource implements OnboardingSource<SourceSpec.Markdown> {

  private static final Logger log = LoggerFactory.getLogger(MarkdownOnboardingSource.class);

  private final ContentIngestor ingestor;

  public MarkdownOnboardingSource(ContentIngestor ingestor) {
    this.ingestor = ingestor;
  }

  @Override
  public Class<SourceSpec.Markdown> specType() {
    return SourceSpec.Markdown.class;
  }

  @Override
  public OnboardResult ingest(String profile, SourceSpec.Markdown spec, IngestProgressListener progress) {
    Path root = Roots.requireFileOrDirectory(spec.root());
    List<Doc> docs = MarkdownDiscovery.create(root).discover(spec.includeAgentDocs());

    List<ContentDocument> documents = new ArrayList<>();
    for (Doc doc : docs) {
      String text = readDoc(doc.absolute());
      if (text != null) {
        documents.add(new ContentDocument("Project documentation — " + doc.relative(), text));
      }
    }
    return ingestor.ingest(profile, "markdown", documents, spec.extractionSamples(),
      Boolean.TRUE.equals(spec.refresh()), progress);
  }

  /** Read a doc as text; a doc that vanished/became unreadable between discovery and read is skipped. */
  private String readDoc(Path absolute) {
    String text = FileOps.readTextQuietly(absolute);
    if (text == null) {
      log.warn("onboard markdown: failed to read {}; skipping", absolute.getFileName());
    }
    return text;
  }
}
