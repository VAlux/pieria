package dev.alvo.pieria.api.request;

/**
 * Body of POST /v1/config/provider/test: provider settings to try before saving them. Every field
 * is optional and falls back to what the daemon is running with. The saved API key is only used
 * when the base URL is unchanged, so a test can never send it to a different host.
 *
 * @param type          {@code openai} (any OpenAI-compatible endpoint) or {@code azure}
 * @param apiVersion    Azure API version; ignored for {@code openai}
 */
public record ProviderTestRequest(String type, String baseUrl, String apiKey, String apiVersion,
                                  String extractionModel, String synthesisModel, String embeddingModel) {
}
