package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.onboarding.WebFetcher.FetchedPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Onboarding source that seeds a profile from one or more web pages. Fetches each URL, extracts its
 * main text, and feeds it through the memory-extraction pipeline via {@link ContentIngestor}. A page
 * that fails to fetch is skipped (logged) so one dead link never fails the whole seed.
 */
@Component
public class WebOnboardingSource implements OnboardingSource<SourceSpec.Web> {

  private static final Logger log = LoggerFactory.getLogger(WebOnboardingSource.class);

  private final ContentIngestor ingestor;
  private final WebFetcher fetcher;

  public WebOnboardingSource(ContentIngestor ingestor, WebFetcher fetcher) {
    this.ingestor = ingestor;
    this.fetcher = fetcher;
  }

  @Override
  public Class<SourceSpec.Web> specType() {
    return SourceSpec.Web.class;
  }

  @Override
  public OnboardResult ingest(String profile, SourceSpec.Web spec, IngestProgressListener progress) {
    List<ContentDocument> documents = new ArrayList<>();
    for (String url : spec.urls()) {
      try {
        FetchedPage page = fetcher.fetch(url);
        if (!page.text().isBlank()) {
          documents.add(new ContentDocument(provenance(url, page.title()), page.text()));
        }
      } catch (RuntimeException e) {
        log.warn("onboard web: failed to fetch {} ({}); skipping", url, e.toString());
      }
    }
    return ingestor.ingest(profile, "web", documents, spec.extractionSamples(),
      Boolean.TRUE.equals(spec.refresh()), progress);
  }

  /** Provenance line for a fetched page: URL plus title when the page has one. */
  private static String provenance(String url, String title) {
    return (title == null || title.isBlank())
      ? "Web page — " + url
      : "Web page — " + title + " (" + url + ")";
  }
}
