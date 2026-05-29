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
}
