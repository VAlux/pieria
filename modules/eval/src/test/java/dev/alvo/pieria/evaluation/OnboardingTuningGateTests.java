package dev.alvo.pieria.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingTuningGateTests {

  @Test
  void lexicalQualityScoringRecognizesSupportedParaphrasesAndRejectsUnrelatedClaims() {
    assertThat(OnboardingEvaluationRunner.matches(
      "The checked-in Gradle wrapper runs the project build.",
      "build runs with the checked-in Gradle wrapper")).isTrue();
    assertThat(OnboardingEvaluationRunner.supportedBy(
      "The daemon is the only SQLite writer.",
      "Architecture: the daemon is the only SQLite writer; the gateway uses HTTP.")).isTrue();
    assertThat(OnboardingEvaluationRunner.supportedBy(
      "Production deploys exclusively to Kubernetes.",
      "The daemon owns SQLite and the gateway uses HTTP.")).isFalse();
  }
  @Test
  void enforcesIndividualAndCombinedThresholdsOverThreeRunMedians() {
    var baseline = variant("baseline", metric(100, 100, 100, .90, .90, .02));
    var individual = variant("queries-2", metric(88, 95, 95, .89, .89, .025));
    var combined = variant("combined", metric(74, 80, 79, .89, .89, .025));

    assertThat(OnboardingTuningGate.assess(baseline, individual, false).qualifies()).isTrue();
    assertThat(OnboardingTuningGate.assess(baseline, combined, true).qualifies()).isTrue();
    assertThat(OnboardingTuningGate.assess(baseline,
      variant("bad-quality", metric(70, 70, 70, .87, .90, .02)), false).qualifies()).isFalse();
  }

  @Test
  void checkedInCorporaCoverSmallMediumAndLarge() {
    List<OnboardingCorpus> corpora = new OnboardingCorpusLoader().loadCheckedIn();
    assertThat(corpora).extracting(OnboardingCorpus::size)
      .containsExactly("small", "medium", "large");
    assertThat(corpora).allSatisfy(corpus -> {
      assertThat(corpus.documents()).isNotEmpty();
      assertThat(corpus.expectedFacts()).isNotEmpty();
      assertThat(corpus.recalls()).isNotEmpty();
    });
  }

  private static OnboardingTuningGate.Variant variant(String name,
                                                       OnboardingTuningGate.RunMetrics metric) {
    return new OnboardingTuningGate.Variant(name, Map.of("provider", "test", "model", "test"),
      List.of(metric, metric, metric));
  }

  private static OnboardingTuningGate.RunMetrics metric(long wall, long calls, long tokens,
                                                         double coverage, double recall,
                                                         double unsupported) {
    return new OnboardingTuningGate.RunMetrics(wall, calls, tokens, coverage, recall, unsupported);
  }
}
