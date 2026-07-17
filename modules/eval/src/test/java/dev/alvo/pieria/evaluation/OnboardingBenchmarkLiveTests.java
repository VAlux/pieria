package dev.alvo.pieria.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Provider-backed onboarding tuning benchmark; deliberately disabled in normal test runs. */
@EnabledIfEnvironmentVariable(named = "PIERIA_ONBOARDING_EVAL", matches = "1|true")
class OnboardingBenchmarkLiveTests {

  private static final Logger log = LoggerFactory.getLogger(OnboardingBenchmarkLiveTests.class);

  @Test
  void comparesAllTuningVariantsAcrossThreeRuns() throws Exception {
    List<OnboardingCorpus> corpora = new OnboardingCorpusLoader().loadCheckedIn();
    assertThat(corpora).extracting(OnboardingCorpus::size)
      .containsExactly("small", "medium", "large");

    try (LiveDaemon daemon = LiveDaemon.start()) {
      DaemonEvalClient client = new DaemonEvalClient(daemon.baseUrl());
      assertThat(client.healthy()).as("daemon health").isTrue();

      OnboardingEvaluationReport report = new OnboardingEvaluationRunner(
        client, daemon.modelMetadata()).run(corpora, OnboardingEvaluationRunner.DEFAULT_RUN_COUNT);
      Path output = new OnboardingEvaluationReportWriter().write(
        report, OnboardingEvaluationRunner.DEFAULT_OUTPUT_DIRECTORY);

      assertThat(report.variants()).hasSize(5);
      assertThat(report.variants()).allSatisfy(variant -> assertThat(variant.runs()).hasSize(3));
      log.info("Onboarding tuning report written to {}", output.toAbsolutePath());
    }
  }
}
