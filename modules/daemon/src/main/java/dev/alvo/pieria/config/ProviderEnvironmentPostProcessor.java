package dev.alvo.pieria.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates Pieria's first-class {@code pieria.provider.type=azure} into the Spring AI Microsoft
 * Foundry switches before the OpenAI autoconfiguration binds. This keeps the clean
 * {@code pieria.provider.*} surface in user config while the underlying
 * {@code spring-ai-starter-model-openai} (built on the OpenAI Java SDK, which speaks Azure natively)
 * does the work.
 *
 * <p>When {@code pieria.provider.type} is {@code azure} it contributes:
 * <ul>
 *   <li>{@code spring.ai.openai.microsoft-foundry=true}</li>
 *   <li>{@code spring.ai.openai.microsoft-foundry-service-version=<pieria.provider.api-version>}</li>
 * </ul>
 * The contributed source is added <em>last</em>, so any explicit user {@code spring.ai.openai.*}
 * override still wins. For {@code type=openai} (the default) or any other value it does nothing, so
 * the existing OpenAI-compatible path is untouched. {@code base-url}/{@code api-key} are not set here
 * — they already flow from {@code pieria.provider.*} via the static {@code application.properties}
 * wiring (the Azure resource endpoint goes in {@code pieria.provider.base-url}).
 */
public class ProviderEnvironmentPostProcessor implements EnvironmentPostProcessor {

  static final String PROPERTY_SOURCE_NAME = "pieriaAzureProviderDefaults";
  private static final String DEFAULT_API_VERSION = "2024-10-21";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    String type = environment.getProperty("pieria.provider.type", "openai");
    if (type == null || !type.strip().equalsIgnoreCase("azure")) {
      return;
    }
    String apiVersion = environment.getProperty("pieria.provider.api-version", DEFAULT_API_VERSION);

    Map<String, Object> azureDefaults = new LinkedHashMap<>();
    azureDefaults.put("spring.ai.openai.microsoft-foundry", "true");
    azureDefaults.put("spring.ai.openai.microsoft-foundry-service-version", apiVersion);

    environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, azureDefaults));
  }
}
