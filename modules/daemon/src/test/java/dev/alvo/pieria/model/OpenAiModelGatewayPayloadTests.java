package dev.alvo.pieria.model;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.ingestion.model.Classification;
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

/**
 * The classifier prompt asks the model to hand-write {@code payload} as a JSON object *string*, so
 * Jackson never parses it and a malformed object reaches the {@code memories.payload} column, where
 * SQLite's {@code json_each} later fails with "malformed JSON". Verifies the gateway drops payloads
 * that are not a JSON object. Driven through a canned {@link ChatModel}, so no live provider needed.
 */
class OpenAiModelGatewayPayloadTests {

  /** Returns a canned assistant message for every call. */
  private static final class CannedChatModel implements ChatModel {
    private final String cannedContent;

    CannedChatModel(String cannedContent) {
      this.cannedContent = cannedContent;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      return new ChatResponse(List.of(new Generation(new AssistantMessage(cannedContent))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
      return OpenAiChatOptions.builder().build();
    }
  }

  private static OpenAiModelGateway gateway(String cannedContent) {
    PieriaProperties properties = new PieriaProperties(null, null, null,
      new PieriaProperties.Model("extract-model", "synth-model", "embed", 1024, 4, null, null), null, null, null);
    ChatClient client = ChatClient.builder(new CannedChatModel(cannedContent))
      .defaultOptions(OpenAiChatOptions.builder().model("extract-model")).build();
    return new OpenAiModelGateway(client, client, null, properties, new OllamaModelProviderAdapter());
  }

  /** A classify response whose {@code payload} string carries {@code inner} verbatim. */
  private static String classifyResponse(String inner) {
    return "{\"type\":\"fact\",\"topicKey\":\"task.audit\","
      + "\"interrogativeQueries\":[\"what is the audit?\"],"
      + "\"payload\":\"" + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
  }

  @Test
  void classifyDropsMalformedJsonPayload() {
    // The exact shape a model produced in the wild: a key:value pair inside a JSON array.
    String malformed = "{\"requirements\":[\"backend coverage\",\"output_file\":\"docs/audit.md\"]}";
    Classification classification = gateway(classifyResponse(malformed)).classify("Audit task");

    assertThat(classification.payload()).isEqualTo("{}");
  }

  @Test
  void classifyDropsPayloadThatIsNotAJsonObject() {
    assertThat(gateway(classifyResponse("[1,2]")).classify("c").payload()).isEqualTo("{}");
    assertThat(gateway(classifyResponse("5")).classify("c").payload()).isEqualTo("{}");
  }

  @Test
  void classifyKeepsWellFormedJsonObjectPayload() {
    String valid = "{\"task_id\":\"BEVJ-003\",\"requirements\":[\"a\",\"b\"]}";
    Classification classification = gateway(classifyResponse(valid)).classify("Audit task");

    assertThat(classification.payload()).isEqualTo(valid);
  }
}
