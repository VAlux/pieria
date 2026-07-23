package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.onboarding.TextDiscovery.Doc;
import dev.alvo.pieria.tools.io.FileOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Onboarding source that seeds a profile from a project's plain-text ({@code *.txt}) documents.
 * Discovers text files under the spec's {@code root} and feeds each doc through the
 * memory-extraction pipeline via {@link ContentIngestor}.
 */
@Component
public class TextOnboardingSource implements OnboardingSource<SourceSpec.Text> {

  private static final Logger log = LoggerFactory.getLogger(TextOnboardingSource.class);

  private final ContentIngestor ingestor;

  public TextOnboardingSource(ContentIngestor ingestor) {
    this.ingestor = ingestor;
  }

  @Override
  public Class<SourceSpec.Text> specType() {
    return SourceSpec.Text.class;
  }

  @Override
  public OnboardingWork begin(String profile, SourceSpec.Text spec, IngestProgressListener progress) {
    Path root = Roots.requireFileOrDirectory(spec.root());
    List<Doc> docs = TextDiscovery.create(root).discover();

    List<ContentDocument> documents = new ArrayList<>();
    for (Doc doc : docs) {
      String text = readDoc(doc.absolute());
      if (text != null && !text.isBlank()) {
        documents.add(new ContentDocument("Text document — " + doc.relative(), text));
      }
    }
    return OnboardingWork.completed(ingestor.ingest(profile, "text", documents,
      spec.extractionSamples(), Boolean.TRUE.equals(spec.refresh()), progress));
  }

  /** Read a doc as text; a doc that vanished/became unreadable between discovery and read is skipped. */
  private String readDoc(Path absolute) {
    String text = FileOps.readTextQuietly(absolute);
    if (text == null) {
      log.warn("onboard text: failed to read {}; skipping", absolute.getFileName());
    }
    return text;
  }
}
