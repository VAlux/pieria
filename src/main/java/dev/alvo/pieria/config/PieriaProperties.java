package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strongly-typed Pieria configuration. The two chat tiers (small/large) and the embedding
 * model are separate knobs from the start (SPEC 4.1) so Phases 2-3 do not reshape config.
 */
@ConfigurationProperties(prefix = "pieria")
public record PieriaProperties(
  Daemon daemon,
  Db db,
  Ollama ollama,
  Model model) {

  public record Daemon(@DefaultValue("127.0.0.1") String host,
                       @DefaultValue("8077") int port) {
  }

  public record Db(String path) {
  }

  public record Ollama(@DefaultValue("http://localhost:11434") String baseUrl) {
  }

  public record Model(String chatSmall,
                      String chatLarge,
                      String embedding,
                      @DefaultValue("1024") int embeddingDimension) {
  }
}
