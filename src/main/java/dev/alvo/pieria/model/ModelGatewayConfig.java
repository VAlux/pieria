package dev.alvo.pieria.model;

import org.springframework.context.annotation.Profile;

import dev.alvo.pieria.config.PieriaProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the two tiered {@link ChatClient} beans (SPEC 4.1) from the autoconfigured
 * {@link OllamaChatModel}. The small client drives structured stages (extraction); the large
 * client is reserved for synthesis. Each client pins its own Ollama model via default options,
 * overriding the {@code spring.ai.ollama.chat.options.model} default so both tiers coexist on a
 * single autoconfigured chat model.
 */
@Configuration
@Profile("!shim")
public class ModelGatewayConfig {

  private static ChatClient chatClientForModel(OllamaChatModel chatModel, String modelName) {
    return ChatClient.builder(chatModel)
      .defaultOptions(OllamaChatOptions.builder().model(modelName))
      .build();
  }

  @Bean("extractionChatClient")
  public ChatClient extractionChatClient(OllamaChatModel chatModel, PieriaProperties properties) {
    return chatClientForModel(chatModel, properties.model().chatSmall());
  }

  @Bean("synthesisChatClient")
  public ChatClient synthesisChatClient(OllamaChatModel chatModel, PieriaProperties properties) {
    return chatClientForModel(chatModel, properties.model().chatLarge());
  }
}
