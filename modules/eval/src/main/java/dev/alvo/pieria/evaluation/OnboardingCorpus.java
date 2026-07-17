package dev.alvo.pieria.evaluation;

import java.util.List;

/** Checked-in onboarding document corpus with labeled facts and recall queries. */
public record OnboardingCorpus(String name, String size, List<Document> documents,
                               List<String> expectedFacts, List<Recall> recalls) {
  public record Document(String name, String text) { }
  public record Recall(String query, List<String> expectedEvidence) { }
}
