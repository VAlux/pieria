package dev.alvo.pieria.model;

import com.openai.azure.AzureOpenAIServiceVersion;
import com.openai.azure.AzureUrlPathMode;
import com.openai.azure.credential.AzureApiKeyCredential;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.credential.BearerTokenCredential;
import com.openai.credential.Credential;
import dev.alvo.pieria.config.PieriaProperties;
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
 *
 * <p>Why we take over client construction:
 * <ul>
 *   <li><b>azure</b> (Azure OpenAI endpoints): the provider speaks the classic
 *       deployment dialect {@code {base}/openai/deployments/{deployment}/…?api-version=}. Spring AI's
 *       setup never sets {@link AzureUrlPathMode}, so its client defaults to {@code AUTO}, fails to
 *       recognize a non-{@code *.openai.azure.com} host as Azure, and posts to a non-deployment URL →
 *       404 on every call. We force {@link AzureUrlPathMode#LEGACY}.</li>
 *   <li><b>openai</b> (Ollama, LM Studio, OpenAI, …): the standard bearer-auth client with the
 *       {@code /v1} API segment baked into the base URL, mirroring the previous autoconfig.</li>
 * </ul>
 *
 * <p>The provider is chosen at <b>runtime</b> with no {@code @ConditionalOnProperty}: Spring AOT
 * evaluates such conditions at native-image build time, where only the {@code openai} default is
 * visible (the real value is imported at runtime), which would bake in the wrong branch.
 */
@Configuration
public class ProviderModelConfig {

  @Bean
  @Primary
  public OpenAiChatModel pieriaChatModel(PieriaProperties properties) {
    PieriaProperties.Provider provider = properties.provider();
    OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
    options.model(properties.model().extractionModel());
    return OpenAiChatModel.builder()
      .openAiClient(syncClient(provider))
      .openAiClientAsync(asyncClient(provider))
      .options(options.build())
      .build();
  }

  @Bean
  @Primary
  public OpenAiEmbeddingModel pieriaEmbeddingModel(PieriaProperties properties) {
    OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder();
    options.model(properties.model().embedding());
    return new OpenAiEmbeddingModel(syncClient(properties.provider()), MetadataMode.EMBED, options.build());
  }

  private static OpenAIClient syncClient(PieriaProperties.Provider provider) {
    OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
      .baseUrl(baseUrl(provider))
      .credential(credential(provider));
    if (provider.isAzure()) {
      builder.azureServiceVersion(AzureOpenAIServiceVersion.fromString(provider.apiVersion()))
        .azureUrlPathMode(AzureUrlPathMode.LEGACY);
    }
    return builder.build();
  }

  /**
   * Async sibling of {@link #syncClient}: {@code OpenAiChatModel} auto-builds an async client from
   * {@code spring.ai.openai.*} when none is supplied, which in azure mode has no credential and
   * throws at construction. Note the async builder spells the path-mode setter {@code azureUrlPath}.
   */
  private static OpenAIClientAsync asyncClient(PieriaProperties.Provider provider) {
    OpenAIOkHttpClientAsync.Builder builder = OpenAIOkHttpClientAsync.builder()
      .baseUrl(baseUrl(provider))
      .credential(credential(provider));
    if (provider.isAzure()) {
      builder.azureServiceVersion(AzureOpenAIServiceVersion.fromString(provider.apiVersion()))
        .azureUrlPath(AzureUrlPathMode.LEGACY);
    }
    return builder.build();
  }

  /** Azure uses {@code Api-Key} header auth; everything else uses bearer-token auth. */
  private static Credential credential(PieriaProperties.Provider provider) {
    return provider.isAzure()
      ? AzureApiKeyCredential.create(provider.apiKey())
      : BearerTokenCredential.create(provider.apiKey());
  }

  /**
   * Azure mode keeps the resource root (the SDK appends {@code /openai/deployments/…}). The OpenAI
   * dialect needs the {@code /v1} API segment baked in, since the SDK posts to
   * {@code {base}/chat/completions} without inserting it.
   */
  private static String baseUrl(PieriaProperties.Provider provider) {
    String base = provider.baseUrl() == null ? "" : provider.baseUrl().strip().replaceAll("/+$", "");
    if (provider.isAzure() || base.isEmpty() || base.endsWith("/v1")) {
      return base;
    }
    return base + "/v1";
  }
}
