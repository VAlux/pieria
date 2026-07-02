package dev.alvo.pieria.model;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.model.provider.AzureModelProviderAdapter;
import dev.alvo.pieria.model.provider.ModelProviderAdapter;
import dev.alvo.pieria.model.provider.OllamaModelProviderAdapter;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Builds the chat/embedding models against an explicitly-constructed {@code com.openai} client,
 * replacing Spring AI's autoconfigured ones (which back off via {@code @ConditionalOnMissingBean}).
 * Client construction and per-call chat options are dialect-specific and delegated to the resolved
 * {@link ModelProviderAdapter} — see {@link OllamaModelProviderAdapter} (any plain OpenAI-compatible
 * endpoint) and {@link AzureModelProviderAdapter} (Azure OpenAI / Microsoft Foundry).
 *
 * <p>The provider is chosen at <b>runtime</b> with no {@code @ConditionalOnProperty}: Spring AOT
 * evaluates such conditions at native-image build time, where only the {@code openai} default is
 * visible (the real value is imported at runtime), which would bake in the wrong branch.
 */
@Configuration
public class ProviderModelConfig {

  @Bean
  public ModelProviderAdapter modelProviderAdapter(PieriaProperties properties) {
    return properties.provider().isAzure() ? new AzureModelProviderAdapter() : new OllamaModelProviderAdapter();
  }

  @Bean
  @Primary
  public OpenAiChatModel pieriaChatModel(PieriaProperties properties, ModelProviderAdapter adapter) {
    OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
    options.model(properties.model().extractionModel());
    return OpenAiChatModel.builder()
      .openAiClient(adapter.buildSyncClient(properties.provider()))
      .openAiClientAsync(adapter.buildAsyncClient(properties.provider()))
      .options(options.build())
      .build();
  }

  @Bean
  @Primary
  public OpenAiEmbeddingModel pieriaEmbeddingModel(PieriaProperties properties, ModelProviderAdapter adapter) {
    OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder();
    options.model(properties.model().embedding());
    return new OpenAiEmbeddingModel(adapter.buildSyncClient(properties.provider()), MetadataMode.EMBED, options.build());
  }
}
