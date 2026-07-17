package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;

/** Loads the small/medium/large checked-in onboarding benchmark corpora. */
public final class OnboardingCorpusLoader {
  public List<OnboardingCorpus> loadCheckedIn() {
    try (InputStream in = getClass().getClassLoader()
      .getResourceAsStream("evaluation/onboarding/corpora.json")) {
      if (in == null) throw new IllegalStateException("checked-in onboarding corpora are missing");
      return new ObjectMapper().readValue(in, new TypeReference<>() { });
    } catch (Exception e) {
      throw new IllegalStateException("failed to load onboarding corpora", e);
    }
  }
}
