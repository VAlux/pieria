package dev.alvo.pieria.model;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.memory.IngestOrigin;
import dev.alvo.pieria.ingestion.model.Chunk;
import dev.alvo.pieria.model.provider.OllamaModelProviderAdapter;
import dev.alvo.pieria.tools.PromptTemplateLoader;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Prompt assembly for external-source content, through a prompt-capturing fake {@link ChatModel}. */
class OpenAiModelGatewayOriginPromptTests {

  private static final IngestOrigin ORIGIN = new IngestOrigin("jira-acme", "jira", "ACME-12", "https://a/12");
  private static final Chunk CHUNK = new Chunk(0, 0, 0, List.of(), "[0] Ann: the db is mysql");

  private static final class CapturingChatModel implements ChatModel {
    final String reply;
    volatile String lastPrompt;

    CapturingChatModel(String reply) {
      this.reply = reply;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      lastPrompt = prompt.getContents();
      return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
      return OpenAiChatOptions.builder().build();
    }
  }

  private static OpenAiModelGateway gateway(CapturingChatModel extraction, CapturingChatModel synthesis) {
    PieriaProperties properties = new PieriaProperties(null, null, null,
      new PieriaProperties.Model("extract-model", "synth-model", "embed", 1024, 4, null, null),
      null, null, null);
    return new OpenAiModelGateway(
      ChatClient.builder(extraction).defaultOptions(OpenAiChatOptions.builder().model("extract-model")).build(),
      ChatClient.builder(synthesis).defaultOptions(OpenAiChatOptions.builder().model("synth-model")).build(),
      null, properties, new OllamaModelProviderAdapter(), null);
  }

  private static String extractionPrompt(Consumer<OpenAiModelGateway> call) {
    CapturingChatModel extraction = new CapturingChatModel("[]");
    call.accept(gateway(extraction, new CapturingChatModel("an answer")));
    return extraction.lastPrompt;
  }

  @Test
  void guidanceAppearsOnlyWithOriginAndIsTheOnlyDifference() {
    String guidance = PromptTemplateLoader.render("source-extraction-guidance", Map.of()).strip();

    String plain = extractionPrompt(gateway -> gateway.extractUnified(CHUNK));
    String withOrigin = extractionPrompt(gateway -> gateway.extractUnified(CHUNK, ORIGIN));

    assertThat(plain).doesNotContain(guidance);
    assertThat(withOrigin).contains(guidance);
    assertThat(withOrigin.replace("\n" + guidance, "")).isEqualTo(plain);
  }
}
