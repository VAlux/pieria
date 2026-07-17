package dev.alvo.pieria.model;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.model.provider.OllamaModelProviderAdapter;
import dev.alvo.pieria.retrieval.model.GraphEvidence;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the synthesis prompt assembly: the code-graph evidence section is present, rendered
 * line-per-edge when evidence exists and as {@code (none)} otherwise, and the memory block is
 * unaffected. Drives the real public surface through a prompt-capturing fake {@link ChatModel},
 * so no live provider is needed.
 */
class OpenAiModelGatewaySynthesisTests {

  /** Records the full prompt text of the last call and returns a canned assistant message. */
  private static final class PromptCapturingChatModel implements ChatModel {
    volatile String lastPrompt;

    @Override
    public ChatResponse call(Prompt prompt) {
      lastPrompt = prompt.getContents();
      return new ChatResponse(List.of(new Generation(new AssistantMessage("an answer"))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
      return OpenAiChatOptions.builder().build();
    }
  }

  private record Harness(OpenAiModelGateway gateway, PromptCapturingChatModel synthesis) {
  }

  private static Harness harness() {
    PromptCapturingChatModel synthesis = new PromptCapturingChatModel();
    PieriaProperties properties = new PieriaProperties(null, null, null,
      new PieriaProperties.Model("extract-model", "synth-model", "embed", 1024, 4, null, null),
      null, null, null);
    OpenAiModelGateway gateway = new OpenAiModelGateway(
      ChatClient.builder(new PromptCapturingChatModel())
        .defaultOptions(OpenAiChatOptions.builder().model("extract-model")).build(),
      ChatClient.builder(synthesis)
        .defaultOptions(OpenAiChatOptions.builder().model("synth-model")).build(),
      null, properties, new OllamaModelProviderAdapter());
    return new Harness(gateway, synthesis);
  }

  private static RecallCandidate candidate(String content) {
    Memory memory = new Memory("m1", "s1", MemoryType.FACT, content, null, null, false, "{}", null,
      Instant.parse("2026-01-01T00:00:00Z"));
    return new RecallCandidate(memory, 1.0, "fts_memory");
  }

  @Test
  void graphEvidenceIsRenderedIntoTheSynthesisPrompt() {
    Harness h = harness();
    GraphEvidence evidence = new GraphEvidence(
      "JGPT#main", "app/src/main/java/dev/alvo/JGPT.java",
      "calls",
      "Model#gpt", "app/src/main/java/dev/alvo/model/Model.java",
      "resolved");

    h.gateway().synthesizeRecall("who calls gpt?", List.of(candidate("a memory")),
      List.of(), List.of(evidence));

    assertThat(h.synthesis().lastPrompt)
      .contains("Code graph evidence")
      .contains("- JGPT#main (app/src/main/java/dev/alvo/JGPT.java) calls "
        + "Model#gpt (app/src/main/java/dev/alvo/model/Model.java) [resolved]")
      .contains("- a memory");
  }

  @Test
  void emptyEvidenceRendersNoneAndKeepsMemoriesBlock() {
    Harness h = harness();

    h.gateway().synthesizeRecall("q", List.of(candidate("a memory")), List.of(), List.of());

    assertThat(h.synthesis().lastPrompt)
      .contains("Code graph evidence")
      .contains("- a memory");
    // The evidence section renders "(none)" — same convention as the temporal facts block.
    assertThat(h.synthesis().lastPrompt.split("Code graph evidence")[1]).contains("(none)");
  }

  @Test
  void threeArgOverloadDelegatesWithNoEvidence() {
    Harness h = harness();

    h.gateway().synthesizeRecall("q", List.of(candidate("a memory")), List.of());

    assertThat(h.synthesis().lastPrompt).contains("Code graph evidence");
  }

  @Test
  void unresolvedTargetRendersRawNameWithoutPath() {
    GraphEvidence evidence = new GraphEvidence(
      "JGPT#main", "JGPT.java", "calls", "gpt", null, "heuristic");
    assertThat(evidence.render()).isEqualTo("JGPT#main (JGPT.java) calls gpt [heuristic]");
  }
}
