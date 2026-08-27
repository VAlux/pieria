package dev.alvo.pieria.model.provider;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.credential.BearerTokenCredential;
import dev.alvo.pieria.config.PieriaProperties;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Dialect for any plain OpenAI-compatible endpoint: Ollama (the default), LM Studio, llama.cpp's
 * server, vLLM, OpenRouter, or OpenAI itself. Bearer-token auth, {@code /v1} baked into the base URL
 * (the OpenAI Java SDK posts to {@code {base}/chat/completions} without inserting it), and
 * {@code reasoning_effort} sent as configured — harmless on models that ignore it (Ollama), and how
 * Qwen3 over Ollama's OpenAI-compatible endpoint is told to skip its reasoning chain, since it does
 * not honor the {@code /no_think} prompt token.
 */
public class OllamaModelProviderAdapter implements ModelProviderAdapter {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static String baseUrl(PieriaProperties.Provider provider) {
    String base = provider.baseUrl() == null ? "" : provider.baseUrl().strip().replaceAll("/+$", "");
    return base.isEmpty() || base.endsWith("/v1") ? base : base + "/v1";
  }

  @Override
  public OpenAIClient buildSyncClient(PieriaProperties.Provider provider) {
    return OpenAIOkHttpClient.builder()
      .baseUrl(baseUrl(provider))
      .credential(BearerTokenCredential.create(provider.apiKey()))
      .build();
  }

  @Override
  public OpenAIClientAsync buildAsyncClient(PieriaProperties.Provider provider) {
    return OpenAIOkHttpClientAsync.builder()
      .baseUrl(baseUrl(provider))
      .credential(BearerTokenCredential.create(provider.apiKey()))
      .build();
  }

  @Override
  public OpenAiChatOptions.Builder chatOptions(String stage, String modelName,
                                               PieriaProperties.Model.Reasoning reasoning) {
    OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(modelName);
    String effort = reasoning.effortFor(stage);
    if (effort != null) {
      builder.reasoningEffort(effort);
    }
    return builder;
  }

  /**
   * Queries the provider's OpenAI-compatible {@code GET /v1/models} and returns the set of available
   * model names ({@code data[].id}). Read-only: never invokes a model or generates tokens. Returns an
   * empty set on any IO/parse failure and never throws.
   */
  @Override
  public Set<String> availableModels(PieriaProperties.Provider provider) {
    String baseUrl = provider.baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      return Set.of();
    }
    String modelsUrl = baseUrl.replaceAll("/+$", "") + "/v1/models";
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) URI.create(modelsUrl).toURL().openConnection();
      conn.setRequestMethod("GET");
      String apiKey = provider.apiKey();
      if (apiKey != null && !apiKey.isBlank()) {
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
      }
      conn.setConnectTimeout(2000);
      conn.setReadTimeout(2000);
      conn.setInstanceFollowRedirects(false);
      int code = conn.getResponseCode();
      if (code < 200 || code >= 300) {
        return Set.of();
      }
      LinkedHashSet<String> names = new LinkedHashSet<>();
      try (InputStream in = conn.getInputStream()) {
        JsonNode root = objectMapper.readTree(in);
        JsonNode models = root.get("data");
        if (models != null && models.isArray()) {
          for (JsonNode model : models) {
            JsonNode id = model.get("id");
            if (id != null && !id.asString().isBlank()) {
              names.add(id.asString().strip());
            }
          }
        }
      }
      return Set.copyOf(names);
    } catch (Exception e) {
      return Set.of();
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }
}
