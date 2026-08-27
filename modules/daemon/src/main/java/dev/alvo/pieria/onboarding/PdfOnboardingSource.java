package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.onboarding.PdfDiscovery.Doc;
import dev.alvo.pieria.onboarding.PdfExtractor.ExtractedPdf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Onboarding source that seeds a profile from a project's PDF documents. Discovers {@code *.pdf}
 * under the spec's {@code root}, extracts each document's text via {@link PdfExtractor}, and feeds it
 * through the memory-extraction pipeline via {@link ContentIngestor}. A document that fails to parse
 * is skipped (logged) so one corrupt PDF never fails the whole seed.
 */
@Component
public class PdfOnboardingSource implements OnboardingSource<SourceSpec.Pdf> {

  private static final Logger log = LoggerFactory.getLogger(PdfOnboardingSource.class);

  private final ContentIngestor ingestor;
  private final PdfExtractor extractor;

  public PdfOnboardingSource(ContentIngestor ingestor, PdfExtractor extractor) {
    this.ingestor = ingestor;
    this.extractor = extractor;
  }

  /**
   * Provenance line for a PDF: relative path plus title when the document has one.
   */
  private static String provenance(String relative, String title) {
    return (title == null || title.isBlank())
      ? "PDF document — " + relative
      : "PDF document — " + title + " (" + relative + ")";
  }

  @Override
  public Class<SourceSpec.Pdf> specType() {
    return SourceSpec.Pdf.class;
  }

  @Override
  public OnboardingWork begin(String profile, SourceSpec.Pdf spec, IngestProgressListener progress) {
    Path root = Roots.requireFileOrDirectory(spec.root());
    List<Doc> docs = PdfDiscovery.create(root).discover();

    List<ContentDocument> documents = new ArrayList<>();
    for (Doc doc : docs) {
      try {
        ExtractedPdf pdf = extractor.extract(doc.absolute());
        if (!pdf.text().isBlank()) {
          documents.add(new ContentDocument(provenance(doc.relative().toString(), pdf.title()), pdf.text()));
        }
      } catch (RuntimeException e) {
        log.warn("onboard pdf: failed to extract {} ({}); skipping", doc.relative(), e.toString());
      }
    }
    return OnboardingWork.completed(ingestor.ingest(profile, "pdf", documents,
      spec.extractionSamples(), Boolean.TRUE.equals(spec.refresh()), progress));
  }
}
