package dev.alvo.pieria.model;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.model.ModelGateway.CodeSummaryInput;
import dev.alvo.pieria.model.ModelGateway.CodeSummaryLevel;
import dev.alvo.pieria.model.provider.OllamaModelProviderAdapter;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the {@code summarizeCode} prompt assembly per level: the right lead-in instruction and
 * the level's evidence (path, outlines, source, child summaries) reach the synthesis model. Drives
 * the real public surface through a prompt-capturing fake {@link ChatModel}, no live provider.
 */
class OpenAiModelGatewayCodeSummaryTests {

  /** Records the full prompt text of the last call and returns a canned assistant message. */
  private static final class PromptCapturingChatModel implements ChatModel {
    volatile String lastPrompt;
    private final boolean fail;

    PromptCapturingChatModel(boolean fail) {
      this.fail = fail;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      if (fail) {
        throw new IllegalStateException("provider down");
      }
      lastPrompt = prompt.getContents();
      return new ChatResponse(List.of(new Generation(new AssistantMessage("a summary"))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
      return OpenAiChatOptions.builder().build();
    }
  }

  private record Harness(OpenAiModelGateway gateway, PromptCapturingChatModel synthesis) {
  }

  private static Harness harness(boolean failSynthesis) {
    PromptCapturingChatModel synthesis = new PromptCapturingChatModel(failSynthesis);
    PieriaProperties properties = new PieriaProperties(null, null, null,
      new PieriaProperties.Model("extract-model", "synth-model", "embed", 1024, 4, null, null),
      null, null, null);
    OpenAiModelGateway gateway = new OpenAiModelGateway(
      ChatClient.builder(new PromptCapturingChatModel(false))
        .defaultOptions(OpenAiChatOptions.builder().model("extract-model")).build(),
      ChatClient.builder(synthesis)
        .defaultOptions(OpenAiChatOptions.builder().model("synth-model")).build(),
      null, properties, new OllamaModelProviderAdapter());
    return new Harness(gateway, synthesis);
  }

  @Test
  void filePromptCarriesPathOutlineAndSource() {
    Harness h = harness(false);

    String out = h.gateway().summarizeCode(new CodeSummaryInput(CodeSummaryLevel.FILE,
      "core/src/A.java", "java",
      List.of("Source file core/src/A.java (java) defines: class A."),
      List.of(), "class A { void run() {} }"));

    assertThat(out).isEqualTo("a summary");
    assertThat(h.synthesis().lastPrompt)
      .contains("explains one source file")
      .contains("Start your answer with \"Source file core/src/A.java:\"")
      .contains("core/src/A.java (java)")
      .contains("- Source file core/src/A.java (java) defines: class A.")
      .contains("class A { void run() {} }");
  }

  @Test
  void modulePromptCarriesOutlinesAndFileSummaries() {
    Harness h = harness(false);

    h.gateway().summarizeCode(new CodeSummaryInput(CodeSummaryLevel.MODULE,
      "core", null,
      List.of("Source file core/src/A.java (java) defines: class A."),
      List.of("Source file core/src/A.java: entry point of the core module."), null));

    assertThat(h.synthesis().lastPrompt)
      .contains("explains one module")
      .contains("Start your answer with \"Module core:\"")
      .contains("- Source file core/src/A.java (java) defines: class A.")
      .contains("- Source file core/src/A.java: entry point of the core module.");
  }

  @Test
  void architecturePromptPrefersModuleSummariesOverListings() {
    Harness h = harness(false);

    h.gateway().summarizeCode(new CodeSummaryInput(CodeSummaryLevel.ARCHITECTURE,
      "pieria", null,
      List.of("core: core/src/A.java"),
      List.of("Module core: the domain layer."), null));

    assertThat(h.synthesis().lastPrompt)
      .contains("architecture overview")
      .contains("Repository: pieria")
      .contains("- Module core: the domain layer.")
      .doesNotContain("core: core/src/A.java");
  }

  @Test
  void architecturePromptFallsBackToListingsWithoutModuleSummaries() {
    Harness h = harness(false);

    h.gateway().summarizeCode(new CodeSummaryInput(CodeSummaryLevel.ARCHITECTURE,
      "pieria", null,
      List.of("core: core/src/A.java"),
      List.of(), null));

    assertThat(h.synthesis().lastPrompt).contains("- core: core/src/A.java");
  }

  @Test
  void providerFailureMapsToModelUnavailable() {
    Harness h = harness(true);

    assertThatThrownBy(() -> h.gateway().summarizeCode(new CodeSummaryInput(
      CodeSummaryLevel.FILE, "A.java", "java", List.of(), List.of(), "class A {}")))
      .isInstanceOf(ModelUnavailableException.class);
  }
}
