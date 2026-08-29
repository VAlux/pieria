package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.ingestion.model.VerificationResult;
import dev.alvo.pieria.ingestion.model.VerificationVerdict;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRecipeExtractorTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  /** Scripted gateway: no network, no Ollama. Records what it was asked. */
  private static final class ScriptedGateway implements ModelGateway {
    final List<String> recipeCalls = new ArrayList<>();
    final List<String> verifyCalls = new ArrayList<>();
    List<TraceRecipe> recipes = List.of();
    VerificationVerdict verdict = VerificationVerdict.PASS;

    @Override
    public List<TraceRecipe> extractTraceRecipes(String traceLog) {
      recipeCalls.add(traceLog);
      return recipes;
    }

    @Override
    public List<VerificationResult> verifyAll(List<String> contents, String transcript) {
      verifyCalls.addAll(contents);
      return contents.stream()
        .map(content -> new VerificationResult(verdict, content, "scripted"))
        .toList();
    }

    @Override
    public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
      return "";
    }

    @Override
    public float[] embed(String text) {
      return new float[0];
    }
  }

  private static TraceEvent event(String args, TraceStatus status, String error) {
    return new TraceEvent("id" + args + status, "s1", "Bash", args, "", status,
      status == TraceStatus.FAILURE ? 1 : 0, error, AT, false, 0);
  }

  // The cost guard: a batch of routine successes on commands already seen yields no new recipe,
  // so it must not pay for a model call at all.
  @Test
  void anAllSuccessBatchOfKnownCommandsSkipsTheModel() {
    ScriptedGateway gateway = new ScriptedGateway();
    TraceRecipeExtractor extractor =
      new TraceRecipeExtractor(gateway, TraceProperties.defaults());

    TraceRecipeExtractor.Result result = extractor.extract(
      List.of(event("./gradlew test", TraceStatus.SUCCESS, null)), Set.of("gradlew-test"));

    assertThat(result.skipped()).isTrue();
    assertThat(result.recipes()).isEmpty();
    assertThat(gateway.recipeCalls).isEmpty();
  }

  @Test
  void aFailureAlwaysEarnsAModelCall() {
    ScriptedGateway gateway = new ScriptedGateway();
    gateway.recipes = List.of(new TraceRecipe("./gradlew test", "Tests run with ./gradlew test."));

    TraceRecipeExtractor.Result result =
      new TraceRecipeExtractor(gateway, TraceProperties.defaults())
        .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")),
          Set.of("gradlew-test"));

    assertThat(result.skipped()).isFalse();
    assertThat(gateway.recipeCalls).hasSize(1);
    assertThat(result.recipes()).hasSize(1);
  }

  @Test
  void anUnseenCommandEarnsAModelCallEvenWhenItSucceeded() {
    ScriptedGateway gateway = new ScriptedGateway();
    gateway.recipes = List.of(new TraceRecipe("npm test", "Front-end tests run with npm test."));

    TraceRecipeExtractor.Result result =
      new TraceRecipeExtractor(gateway, TraceProperties.defaults())
        .extract(List.of(event("npm test", TraceStatus.SUCCESS, null)), Set.of("gradlew-test"));

    assertThat(result.skipped()).isFalse();
    assertThat(gateway.recipeCalls).hasSize(1);
  }

  // Exactly one call for the whole batch, and the log must preserve arrival order so a
  // failure and the fix that followed it are visible together.
  @Test
  void oneCallCarriesTheWholeBatchInOrder() {
    ScriptedGateway gateway = new ScriptedGateway();

    new TraceRecipeExtractor(gateway, TraceProperties.defaults()).extract(
      List.of(event("./gradlew test", TraceStatus.FAILURE, "boom"),
        event("./gradlew test", TraceStatus.SUCCESS, null)),
      Set.of());

    assertThat(gateway.recipeCalls).hasSize(1);
    String log = gateway.recipeCalls.getFirst();
    assertThat(log.indexOf("failure")).isLessThan(log.indexOf("success"));
  }

  // A statement plainly grounded in the log skips the model verifier, exactly as the
  // conversational path does.
  @Test
  void aGroundedStatementSkipsVerification() {
    ScriptedGateway gateway = new ScriptedGateway();
    gateway.recipes = List.of(
      new TraceRecipe("./gradlew test", "./gradlew test failed with exit 1 and boom"));

    TraceRecipeExtractor.Result result =
      new TraceRecipeExtractor(gateway, TraceProperties.defaults())
        .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")), Set.of());

    assertThat(gateway.verifyCalls).isEmpty();
    assertThat(result.recipes()).hasSize(1);
  }

  @Test
  void anUngroundedStatementIsVerifiedAndDroppedOnADropVerdict() {
    ScriptedGateway gateway = new ScriptedGateway();
    gateway.verdict = VerificationVerdict.DROP;
    gateway.recipes = List.of(new TraceRecipe("./gradlew test",
      "Deployment to production requires an approval from the release manager."));

    TraceRecipeExtractor.Result result =
      new TraceRecipeExtractor(gateway, TraceProperties.defaults())
        .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")), Set.of());

    assertThat(gateway.verifyCalls).hasSize(1);
    assertThat(result.recipes()).isEmpty();
    assertThat(result.dropped()).isEqualTo(1);
  }

  @Test
  void aCorrectVerdictReplacesTheStatement() {
    ScriptedGateway gateway = new ScriptedGateway();
    gateway.verdict = VerificationVerdict.CORRECT;
    gateway.recipes = List.of(new TraceRecipe("./gradlew test", "Something loosely related here."));

    TraceRecipeExtractor.Result result =
      new TraceRecipeExtractor(gateway, TraceProperties.defaults())
        .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")), Set.of());

    assertThat(result.recipes()).hasSize(1);
    assertThat(result.recipes().getFirst().statement()).isEqualTo("Something loosely related here.");
  }

  @Test
  void recipesAreCappedPerBatch() {
    TraceProperties d = TraceProperties.defaults();
    TraceProperties capped = new TraceProperties(d.enabled(), d.maxOutputChars(), d.spoolMaxBytes(),
      d.spoolRetentionDays(), d.stopDrainThresholdBytes(), d.stopDrainThresholdEvents(),
      d.toolDenylist(), d.skipUnchangedOutcomes(), d.recipeExtractionEnabled(), 1,
      d.maxLinkedSymbols(), d.recallBoost());

    ScriptedGateway gateway = new ScriptedGateway();
    gateway.recipes = List.of(
      new TraceRecipe("./gradlew test", "./gradlew test failed with boom"),
      new TraceRecipe("./gradlew build", "./gradlew build failed with boom"));

    TraceRecipeExtractor.Result result = new TraceRecipeExtractor(gateway, capped)
      .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")), Set.of());

    assertThat(result.recipes()).hasSize(1);
  }

  @Test
  void extractionCanBeDisabled() {
    TraceProperties d = TraceProperties.defaults();
    TraceProperties off = new TraceProperties(d.enabled(), d.maxOutputChars(), d.spoolMaxBytes(),
      d.spoolRetentionDays(), d.stopDrainThresholdBytes(), d.stopDrainThresholdEvents(),
      d.toolDenylist(), d.skipUnchangedOutcomes(), false, d.maxRecipesPerBatch(),
      d.maxLinkedSymbols(), d.recallBoost());

    ScriptedGateway gateway = new ScriptedGateway();

    assertThat(new TraceRecipeExtractor(gateway, off)
      .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")), Set.of()).skipped())
      .isTrue();
    assertThat(gateway.recipeCalls).isEmpty();
  }

  // Recipe derivation is additive: a model failure must lose the recipes, never the events.
  @Test
  void aModelFailureYieldsNoRecipesRatherThanPropagating() {
    ModelGateway exploding = new ModelGateway() {
      @Override
      public List<TraceRecipe> extractTraceRecipes(String traceLog) {
        throw new IllegalStateException("provider down");
      }

      @Override
      public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
        return "";
      }

      @Override
      public float[] embed(String text) {
        return new float[0];
      }
    };

    TraceRecipeExtractor.Result result =
      new TraceRecipeExtractor(exploding, TraceProperties.defaults())
        .extract(List.of(event("./gradlew test", TraceStatus.FAILURE, "boom")), Set.of());

    assertThat(result.recipes()).isEmpty();
  }

  @Test
  void anEmptyBatchSkips() {
    ScriptedGateway gateway = new ScriptedGateway();

    assertThat(new TraceRecipeExtractor(gateway, TraceProperties.defaults())
      .extract(List.of(), Set.of()).skipped()).isTrue();
  }
}
