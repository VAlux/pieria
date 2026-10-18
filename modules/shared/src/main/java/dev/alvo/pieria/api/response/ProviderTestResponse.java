package dev.alvo.pieria.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result of POST /v1/config/provider/test: one tiny call per model tier. {@code error} is a
 * sanitized description that never contains the API key or the provider host.
 */
public record ProviderTestResponse(List<Tier> tiers) {

  /**
   * @param tier                {@code extraction}, {@code synthesis} or {@code embedding}
   * @param errorKind           {@code unauthorized}, {@code not_found}, {@code rate_limited},
   *                            {@code unreachable}, {@code api_key_required} or {@code error}
   * @param embeddingDimensions vector width the embedding model returned (embedding tier only)
   * @param dimensionMatches    whether that width equals the store's fixed dimension
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Tier(String tier, String model, boolean reachable, long latencyMs, String errorKind, String error,
                     Integer embeddingDimensions, Boolean dimensionMatches) {
  }
}
