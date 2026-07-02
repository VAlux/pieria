package dev.alvo.pieria.model.provider;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import dev.alvo.pieria.config.PieriaProperties;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Set;

/**
 * Isolates the wire-dialect differences between model providers behind one seam: transport client
 * construction, per-call chat options, and model discovery. {@link dev.alvo.pieria.model.ModelGateway}
 * and {@link dev.alvo.pieria.model.ProviderModelConfig} depend only on this interface, never on
 * {@code provider.type()} directly, so adding a new provider dialect means adding one implementation
 * rather than threading another {@code if (isAzure())} branch through the gateway.
 *
 * <p>Resolved once at startup from {@code pieria.provider.type} — see the {@code modelProviderAdapter}
 * bean in {@code ProviderModelConfig}.
 */
public interface ModelProviderAdapter {

  /** Build the synchronous {@code com.openai} client for this dialect. */
  OpenAIClient buildSyncClient(PieriaProperties.Provider provider);

  /** Build the asynchronous {@code com.openai} client for this dialect. */
  OpenAIClientAsync buildAsyncClient(PieriaProperties.Provider provider);

  /**
   * Build the per-call chat options for {@code stage} on {@code modelName}, applying whatever subset
   * of {@code reasoning} this dialect actually supports.
   */
  OpenAiChatOptions.Builder chatOptions(String stage, String modelName, PieriaProperties.Model.Reasoning reasoning);

  /**
   * Model names the provider currently reports as available, for log-only first-run guidance. Must
   * never throw; return an empty set when the dialect has no discovery endpoint or the probe fails.
   */
  Set<String> availableModels(PieriaProperties.Provider provider);
}
