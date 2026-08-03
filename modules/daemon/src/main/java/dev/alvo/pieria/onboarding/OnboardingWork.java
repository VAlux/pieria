package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.response.OnboardResult;
import dev.alvo.pieria.ingestion.IngestProgressListener;

/** Deferred completion stage of one onboarding source. */
@FunctionalInterface
public interface OnboardingWork {

  OnboardResult finish(IngestProgressListener progress);

  static OnboardingWork completed(OnboardResult result) {
    return ignored -> result;
  }
}
