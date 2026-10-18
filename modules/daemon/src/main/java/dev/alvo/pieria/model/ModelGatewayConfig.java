package dev.alvo.pieria.model;


import dev.alvo.pieria.config.PieriaProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the two tiered {@link ChatClient} beans from the autoconfigured
 * {@link OpenAiChatModel}. The small client drives structured stages (extraction); the large
 * client is reserved for synthesis. Each client pins its own model via default options, overriding
 * the {@code spring.ai.openai.chat.options.model} default so both tiers coexist on a single
 * autoconfigured chat model. The provider behind {@link OpenAiChatModel} is any OpenAI-compatible
 * endpoint configured via {@code pieria.provider.*} (Ollama, LM Studio, llama.cpp, vLLM, OpenAI, …).
 */
@Configuration
public class ModelGatewayConfig {

  private static ChatClient chatClientForModel(OpenAiChatModel chatModel, String modelName) {
    return ChatClient.builder(chatModel)
      .defaultOptions(OpenAiChatOptions.builder().model(modelName))
      .build();
  }

  @Bean("extractionChatClient")
  public ChatClient extractionChatClient(OpenAiChatModel chatModel, PieriaProperties properties) {
    return chatClientForModel(chatModel, properties.model().extractionModel());
  }

  @Bean("synthesisChatClient")
  public ChatClient synthesisChatClient(OpenAiChatModel chatModel, PieriaProperties properties) {
    return chatClientForModel(chatModel, properties.model().synthesisModel());
  }

  @Bean("wikiChatClient")
  public ChatClient wikiChatClient(PieriaProperties properties,
      dev.alvo.pieria.model.provider.ModelProviderAdapter adapter, dev.alvo.pieria.config.WikiProperties wiki) {
    // The durable wiki queue owns retries. Suppress retries in the transport too, rather than
    // multiplying the queue's two attempts by the SDK's retry policy.
    var timeout = java.time.Duration.ofSeconds(wiki.timeoutSeconds());
    var model = OpenAiChatModel.builder()
      .openAiClient(adapter.buildSyncClient(properties.provider()).withOptions(o -> o.maxRetries(0).timeout(timeout)))
      .openAiClientAsync(adapter.buildAsyncClient(properties.provider()).withOptions(o -> o.maxRetries(0).timeout(timeout)))
      .options(OpenAiChatOptions.builder().model(properties.model().synthesisModel()).build()).build();
    return chatClientForModel(model, properties.model().synthesisModel());
  }
}
