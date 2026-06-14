package dev.alvo.pieria.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates Pieria's clean {@code pieria.provider.*} surface into the underlying
 * {@code spring.ai.openai.*} wiring before the OpenAI autoconfiguration binds, so user config never
 * has to know how Spring AI / the OpenAI Java SDK expects the base URL or the Azure switches.
 *
 * <p>It contributes {@code spring.ai.openai.base-url} for both dialects:
 * <ul>
 *   <li><b>openai</b> (default): {@code <pieria.provider.base-url>/v1}. The OpenAI Java SDK that
 *       backs Spring AI 2.0 posts to {@code {base-url}/chat/completions} <em>without</em> inserting
 *       the {@code /v1} API segment, so it must already be present. Without it, OpenAI-compatible
 *       providers such as Ollama return 404 for every model call (surfaced as
 *       {@code ModelUnavailableException}). Keeping the {@code /v1} out of
 *       {@code pieria.provider.base-url} lets the daemon's own probes ({@code isModelProviderReachable},
 *       {@code availableModels}) continue to treat it as the bare API root.</li>
 *   <li><b>azure</b>: the resource endpoint is passed through unchanged and the Microsoft Foundry
 *       switches are added — the SDK speaks Azure natively and builds deployment URLs itself.</li>
 * </ul>
 * The contributed source is added <em>last</em>, so any explicit user {@code spring.ai.openai.*}
 * override still wins. {@code api-key} is not set here — it flows from {@code pieria.provider.api-key}
 * via the static {@code application.properties} wiring.
 */
public class ProviderEnvironmentPostProcessor implements EnvironmentPostProcessor {

  static final String PROPERTY_SOURCE_NAME = "pieriaProviderDefaults";
  private static final String DEFAULT_API_VERSION = "2024-10-21";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    String type = environment.getProperty("pieria.provider.type", "openai");
    boolean azure = type != null && type.strip().equalsIgnoreCase("azure");

    Map<String, Object> contributed = new LinkedHashMap<>();

    String baseUrl = environment.getProperty("pieria.provider.base-url", "");
    if (baseUrl != null && !baseUrl.isBlank()) {
      String normalized = baseUrl.strip().replaceAll("/+$", "");
      // OpenAI dialect needs the /v1 API segment baked into the base URL (the SDK does not add it);
      // Azure constructs its own URLs, so pass the resource endpoint through untouched.
      String wired = (!azure && !normalized.endsWith("/v1")) ? normalized + "/v1" : normalized;
      contributed.put("spring.ai.openai.base-url", wired);
    }

    if (azure) {
      String apiVersion = environment.getProperty("pieria.provider.api-version", DEFAULT_API_VERSION);
      contributed.put("spring.ai.openai.microsoft-foundry", "true");
      contributed.put("spring.ai.openai.microsoft-foundry-service-version", apiVersion);
    }

    if (!contributed.isEmpty()) {
      environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, contributed));
    }
  }
}
