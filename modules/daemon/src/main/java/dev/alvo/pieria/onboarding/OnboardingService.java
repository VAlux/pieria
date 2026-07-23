package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes an onboarding request to the {@link OnboardingSource} that handles its {@link SourceSpec}
 * subtype. The registry is built from every {@code OnboardingSource} bean, so adding a source is
 * purely additive — no switch to extend here. This is the single entry point the controller submits
 * as a background task.
 */
@Service
public class OnboardingService {

  private final Map<Class<? extends SourceSpec>, OnboardingSource<?>> registry = new HashMap<>();

  public OnboardingService(List<OnboardingSource<?>> sources) {
    for (OnboardingSource<?> source : sources) {
      OnboardingSource<?> previous = registry.put(source.specType(), source);
      if (previous != null) {
        throw new IllegalStateException("Two onboarding sources claim spec type " + source.specType()
          + ": " + previous.getClass() + " and " + source.getClass());
      }
    }
  }

  /**
   * Discover and ingest {@code spec} into {@code profile}, reporting progress. Throws
   * {@link IllegalArgumentException} (→ HTTP 400) when no source handles the spec's type.
   */
  @SuppressWarnings("unchecked")
  public OnboardingWork begin(String profile, SourceSpec spec, IngestProgressListener progress) {
    OnboardingSource<SourceSpec> source =
      (OnboardingSource<SourceSpec>) registry.get(spec.getClass());
    if (source == null) {
      throw new IllegalArgumentException("no onboarding source for spec type " + spec.getClass().getSimpleName());
    }
    return source.begin(profile, spec, progress);
  }
}
