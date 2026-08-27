package dev.alvo.pieria.model.provider;

import com.openai.azure.AzureOpenAIServiceVersion;
import com.openai.azure.AzureUrlPathMode;
import com.openai.azure.credential.AzureApiKeyCredential;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import dev.alvo.pieria.config.PieriaProperties;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Set;

/**
 * Dialect for Azure OpenAI / Microsoft Foundry that speak the classic deployment URL scheme: {@code {base}/openai/deployments/{deployment}/...
 * ?api-version=}. {@code Api-Key} header auth, {@link AzureUrlPathMode#LEGACY} forced (Spring AI never
 * sets it, so the SDK's {@code AUTO} default fails to recognize a non-{@code *.openai.azure.com} host
 * and posts to a non-deployment URL — 404 on every call), and the resource root kept as-is (the SDK
 * appends {@code /openai/deployments/...} itself).
 *
 * <p>{@code reasoning_effort} is never sent: it is only a valid request argument for deployments of
 * reasoning-tier models (the {@code o1}/{@code o3}/{@code o4}/{@code -reasoning} family), and a plain
 * chat deployment (e.g. {@code gpt-4o}, {@code gpt-4.1-mini}) rejects the argument outright with
 * {@code HTTP 400: Unrecognized request argument supplied: reasoning_effort} — unlike Ollama, where
 * the option is simply ignored by models that don't understand it. Pieria's structured/synthesis
 * tiers are deliberately non-reasoning models, so omitting it entirely is the correct default; a
 * reasoning-aware Azure deployment would need its own adapter variant.
 */
public class AzureModelProviderAdapter implements ModelProviderAdapter {

  private static String baseUrl(PieriaProperties.Provider provider) {
    return provider.baseUrl() == null ? "" : provider.baseUrl().strip().replaceAll("/+$", "");
  }

  @Override
  public OpenAIClient buildSyncClient(PieriaProperties.Provider provider) {
    return OpenAIOkHttpClient.builder()
      .baseUrl(baseUrl(provider))
      .credential(AzureApiKeyCredential.create(provider.apiKey()))
      .azureServiceVersion(AzureOpenAIServiceVersion.fromString(provider.apiVersion()))
      .azureUrlPathMode(AzureUrlPathMode.LEGACY)
      .build();
  }

  @Override
  public OpenAIClientAsync buildAsyncClient(PieriaProperties.Provider provider) {
    return OpenAIOkHttpClientAsync.builder()
      .baseUrl(baseUrl(provider))
      .credential(AzureApiKeyCredential.create(provider.apiKey()))
      .azureServiceVersion(AzureOpenAIServiceVersion.fromString(provider.apiVersion()))
      .azureUrlPath(AzureUrlPathMode.LEGACY)
      .build();
  }

  @Override
  public OpenAiChatOptions.Builder chatOptions(String stage, String modelName,
                                               PieriaProperties.Model.Reasoning reasoning) {
    return OpenAiChatOptions.builder().model(modelName);
  }

  /**
   * Azure does not expose models at {@code /v1/models} (it lists <em>deployments</em> at a different,
   * api-version'd path), so first-run guidance simply skips the probe here.
   */
  @Override
  public Set<String> availableModels(PieriaProperties.Provider provider) {
    return Set.of();
  }
}
