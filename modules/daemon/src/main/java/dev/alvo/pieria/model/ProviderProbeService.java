package dev.alvo.pieria.model;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.embeddings.EmbeddingCreateParams;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.model.provider.AzureModelProviderAdapter;
import dev.alvo.pieria.model.provider.ModelProviderAdapter;
import dev.alvo.pieria.model.provider.OllamaModelProviderAdapter;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Tries provider settings with one minimal call per model tier, so the console can say whether a
 * provider, key and model names work before they are saved. Each chat probe spends a handful of
 * tokens; the embedding probe embeds one word and reports the vector width.
 */
@Service
public class ProviderProbeService {

  static final Duration TIMEOUT = Duration.ofSeconds(15);

  private final PieriaProperties properties;

  public ProviderProbeService(PieriaProperties properties) {
    this.properties = properties;
  }

  /** Settings to probe; null fields mean "what the daemon runs with". */
  public record Probe(String type, String baseUrl, String apiKey, String apiVersion,
                      String extractionModel, String synthesisModel, String embeddingModel) {
  }

  public record TierResult(String tier, String model, boolean reachable, long latencyMs, String errorKind,
                           String error, Integer embeddingDimensions, Boolean dimensionMatches) {

    static TierResult failed(String tier, String model, long latencyMs, String kind, String error) {
      return new TierResult(tier, model, false, latencyMs, kind, error, null, null);
    }
  }

  public List<TierResult> probe(Probe request) {
    PieriaProperties.Provider running = properties.provider();
    PieriaProperties.Model models = properties.model();
    String baseUrl = blank(request.baseUrl()) ? running.baseUrl() : request.baseUrl().strip();
    String extraction = blank(request.extractionModel()) ? models.extractionModel() : request.extractionModel().strip();
    String synthesis = blank(request.synthesisModel()) ? models.synthesisModel() : request.synthesisModel().strip();
    String embedding = blank(request.embeddingModel()) ? models.embedding() : request.embeddingModel().strip();

    // The stored key goes only to the host it was configured for.
    boolean sameHost = Objects.equals(normalize(baseUrl), normalize(running.baseUrl()));
    if (blank(request.apiKey()) && !sameHost) {
      String message = "Enter the API key for this base URL; the saved key is only sent to the saved base URL.";
      return List.of(
        TierResult.failed("extraction", extraction, 0, "api_key_required", message),
        TierResult.failed("synthesis", synthesis, 0, "api_key_required", message),
        TierResult.failed("embedding", embedding, 0, "api_key_required", message));
    }
    PieriaProperties.Provider provider = new PieriaProperties.Provider(baseUrl,
      blank(request.apiKey()) ? running.apiKey() : request.apiKey(),
      running.name(),
      blank(request.type()) ? running.type() : request.type().strip(),
      blank(request.apiVersion()) ? running.apiVersion() : request.apiVersion().strip());
    ModelProviderAdapter adapter = provider.isAzure() ? new AzureModelProviderAdapter() : new OllamaModelProviderAdapter();
    OpenAIClient client = adapter.buildSyncClient(provider).withOptions(o -> o.maxRetries(0).timeout(TIMEOUT));

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<TierResult> small = pool.submit(() -> chat(client, "extraction", extraction));
      Future<TierResult> large = pool.submit(() -> chat(client, "synthesis", synthesis));
      Future<TierResult> vectors = pool.submit(() -> embed(client, embedding, models.embeddingDimension()));
      return List.of(small.get(), large.get(), vectors.get());
    } catch (Exception e) {
      throw new IllegalStateException("provider probe was interrupted", e);
    }
  }

  private static TierResult chat(OpenAIClient client, String tier, String model) {
    long start = System.nanoTime();
    try {
      client.chat().completions().create(ChatCompletionCreateParams.builder()
        .model(model)
        .addUserMessage("Reply with OK.")
        .maxCompletionTokens(16)
        .build());
      return new TierResult(tier, model, true, millis(start), null, null, null, null);
    } catch (RuntimeException e) {
      return TierResult.failed(tier, model, millis(start), kind(e), ModelFailures.describe(e));
    }
  }

  private static TierResult embed(OpenAIClient client, String model, int expected) {
    long start = System.nanoTime();
    try {
      var response = client.embeddings().create(EmbeddingCreateParams.builder().model(model).input("ping").build());
      int width = response.data().isEmpty() ? 0 : response.data().getFirst().embedding().size();
      return new TierResult("embedding", model, true, millis(start), null, null, width, width == expected);
    } catch (RuntimeException e) {
      return TierResult.failed("embedding", model, millis(start), kind(e), ModelFailures.describe(e));
    }
  }

  private static String kind(Throwable e) {
    for (Throwable c = e; c != null && c != c.getCause(); c = c.getCause()) {
      if (c instanceof OpenAIServiceException http) {
        return switch (http.statusCode()) {
          case 401, 403 -> "unauthorized";
          case 404 -> "not_found";
          case 429 -> "rate_limited";
          default -> "error";
        };
      }
    }
    return ModelFailures.isTransient(e) || ModelFailures.describe(e).startsWith("cannot reach") ? "unreachable" : "error";
  }

  private static long millis(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String normalize(String url) {
    return url == null ? "" : url.strip().replaceAll("/+$", "").replaceAll("/v1$", "").toLowerCase(java.util.Locale.ROOT);
  }
}
