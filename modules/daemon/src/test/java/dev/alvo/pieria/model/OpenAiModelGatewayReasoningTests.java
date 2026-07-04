package dev.alvo.pieria.model;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.PieriaProperties.Model.Reasoning;
import dev.alvo.pieria.ingestion.model.ExtractedCandidate;
import dev.alvo.pieria.model.provider.AzureModelProviderAdapter;
import dev.alvo.pieria.model.provider.ModelProviderAdapter;
import dev.alvo.pieria.model.provider.OllamaModelProviderAdapter;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies per-stage reasoning control: the gateway sends the configured {@code reasoning_effort}
 * option per stage (disabled stages → {@code none}; enabled stages → unset by default). Drives the
 * real public surface ({@code verify}, {@code synthesizeRecall}) through a {@link ChatClient} backed
 * by an option-capturing fake {@link ChatModel}, so no live provider is needed.
 */
class OpenAiModelGatewayReasoningTests {

  /** Records the merged options of the last call and returns a canned assistant message. */
  private static final class CapturingChatModel implements ChatModel {
    volatile ChatOptions lastOptions;
    private final String cannedContent;

    CapturingChatModel(String cannedContent) {
      this.cannedContent = cannedContent;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      lastOptions = prompt.getOptions();
      return new ChatResponse(List.of(new Generation(new AssistantMessage(cannedContent))));
    }

    // The ChatClient merges runtime options into the model's default-options type; an OpenAI-typed
    // default keeps the OpenAI-only reasoning_effort field through the merge.
    @Override
    public ChatOptions getDefaultOptions() {
      return OpenAiChatOptions.builder().build();
    }
  }

  private record Harness(OpenAiModelGateway gateway,
                         CapturingChatModel extraction,
                         CapturingChatModel synthesis) {
  }

  private static Harness harness(Reasoning reasoning) {
    return harness(reasoning, new OllamaModelProviderAdapter());
  }

  private static Harness harness(Reasoning reasoning, ModelProviderAdapter providerAdapter) {
    CapturingChatModel extraction = new CapturingChatModel("{\"verdict\":\"drop\",\"content\":\"\",\"reason\":\"x\"}");
    CapturingChatModel synthesis = new CapturingChatModel("an answer");
    PieriaProperties properties = new PieriaProperties(null, null, null,
      new PieriaProperties.Model("extract-model", "synth-model", "embed", 1024, reasoning),
      null, null, null);
    // Mirror ModelGatewayConfig: OpenAI-typed default options, so the ChatClient merges the runtime
    // reasoning_effort into an OpenAiChatOptions (a generic default would drop the OpenAI-only field).
    OpenAiModelGateway gateway = new OpenAiModelGateway(
      ChatClient.builder(extraction).defaultOptions(OpenAiChatOptions.builder().model("extract-model")).build(),
      ChatClient.builder(synthesis).defaultOptions(OpenAiChatOptions.builder().model("synth-model")).build(),
      null, properties, providerAdapter);
    return new Harness(gateway, extraction, synthesis);
  }

  private static String effortOf(ChatOptions options) {
    return ((OpenAiChatOptions) options).getReasoningEffort();
  }

  private static ExtractedCandidate candidate() {
    return new ExtractedCandidate("the user prefers dark roast coffee", null, 0, "extract");
  }

  @Test
  void structuredStageSendsNoneEffortByDefault() {
    Harness h = harness(null); // null → all-defaults: structured off (none), synthesis off (none)

    h.gateway().verify(candidate(), "transcript");

    assertThat(effortOf(h.extraction().lastOptions)).isEqualTo("none");
  }

  @Test
  void azureAdapterNeverSendsReasoningEffortEvenWhenConfigured() {
    Harness h = harness(new Reasoning(false, true, "none", "low", Map.of()), new AzureModelProviderAdapter());

    h.gateway().verify(candidate(), "transcript");
    h.gateway().synthesizeRecall("q", List.<RecallCandidate>of());

    assertThat(effortOf(h.extraction().lastOptions)).isNull();
    assertThat(effortOf(h.synthesis().lastOptions)).isNull();
  }

  @Test
  void synthesisSendsNoneEffortByDefault() {
    Harness h = harness(null); // synthesis reasoning is off by default → disabledEffort ("none")

    h.gateway().synthesizeRecall("what coffee?", List.<RecallCandidate>of());

    assertThat(effortOf(h.synthesis().lastOptions)).isEqualTo("none");
  }

  @Test
  void perStageOverrideReenablesReasoningForVerify() {
    Harness h = harness(new Reasoning(false, true, "none", "", Map.of("verify", true)));

    h.gateway().verify(candidate(), "transcript");

    assertThat(effortOf(h.extraction().lastOptions)).isNull();
  }

  @Test
  void enabledEffortIsSentForEnabledStages() {
    Harness h = harness(new Reasoning(false, true, "none", "low", Map.of()));

    h.gateway().synthesizeRecall("q", List.<RecallCandidate>of());

    assertThat(effortOf(h.synthesis().lastOptions)).isEqualTo("low");
  }

  @Test
  void effortForAppliesTierDefaultsAndOverrides() {
    Reasoning defaults = Reasoning.DEFAULT;
    assertThat(defaults.enabledFor("verify")).isFalse();
    assertThat(defaults.enabledFor("synthesizeRecall")).isFalse();
    assertThat(defaults.effortFor("verify")).isEqualTo("none");
    assertThat(defaults.effortFor("extract")).isEqualTo("none");
    assertThat(defaults.effortFor("synthesizeRecall")).isEqualTo("none");
    assertThat(defaults.effortFor("judgeAnswerFaithfulness")).isEqualTo("none");

    Reasoning overridden = new Reasoning(false, true, "none", "medium",
      Map.of("verify", true, "synthesizeRecall", false));
    assertThat(overridden.effortFor("verify")).isEqualTo("medium");
    assertThat(overridden.effortFor("synthesizeRecall")).isEqualTo("none");
    assertThat(overridden.effortFor("classify")).isEqualTo("none");
  }
}
