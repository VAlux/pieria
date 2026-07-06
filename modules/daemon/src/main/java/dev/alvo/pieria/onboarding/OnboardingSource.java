package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.ingestion.IngestProgressListener;

/**
 * A kind of onboarding source: discovers content from somewhere ({@code root} on disk, a set of
 * URLs, …) and ingests it into a profile. This is the daemon-side extension point behind the
 * polymorphic {@link SourceSpec} wire contract — one implementation per {@code SourceSpec} subtype,
 * discovered by {@link OnboardingService} through Spring and dispatched on {@link #specType()}.
 *
 * <p>Adding a new source (a Slack export, a Notion space, …) is: a new {@code SourceSpec} subtype
 * plus a new {@code @Component} implementing this interface. Content sources that feed the
 * memory-extraction pipeline should build {@link ContentDocument}s and hand them to
 * {@link ContentIngestor}; the source-code source targets the code-index pipeline directly.
 *
 * @param <S> the {@link SourceSpec} subtype this source handles
 */
public interface OnboardingSource<S extends SourceSpec> {

  /** The concrete spec subtype this source handles; used by the registry to route a request. */
  Class<S> specType();

  /**
   * Discover and ingest the source into {@code profile}, reporting coarse pipeline progress through
   * {@code progress}. Runs on a background task, so it may block on model calls / IO.
   */
  OnboardResult ingest(String profile, S spec, IngestProgressListener progress);
}
