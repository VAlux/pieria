package dev.alvo.pieria.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code pieria.provider.type=azure} → Spring AI Microsoft Foundry translation. Pure
 * unit test over a {@link MockEnvironment}; no Spring context, no network.
 */
class ProviderEnvironmentPostProcessorTests {

  private final ProviderEnvironmentPostProcessor processor = new ProviderEnvironmentPostProcessor();
  private final SpringApplication application = new SpringApplication();

  @Test
  void azureTypeContributesFoundrySwitches() {
    MockEnvironment env = new MockEnvironment()
      .withProperty("pieria.provider.type", "azure")
      .withProperty("pieria.provider.api-version", "2024-10-21");

    processor.postProcessEnvironment(env, application);

    assertThat(env.getPropertySources().contains(ProviderEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isTrue();
    assertThat(env.getProperty("spring.ai.openai.microsoft-foundry")).isEqualTo("true");
    assertThat(env.getProperty("spring.ai.openai.microsoft-foundry-service-version")).isEqualTo("2024-10-21");
  }

  @Test
  void azureTypeIsCaseInsensitiveAndDefaultsApiVersion() {
    MockEnvironment env = new MockEnvironment().withProperty("pieria.provider.type", "Azure");

    processor.postProcessEnvironment(env, application);

    assertThat(env.getProperty("spring.ai.openai.microsoft-foundry")).isEqualTo("true");
    assertThat(env.getProperty("spring.ai.openai.microsoft-foundry-service-version")).isEqualTo("2024-10-21");
  }

  @Test
  void openAiTypeContributesNothing() {
    MockEnvironment env = new MockEnvironment().withProperty("pieria.provider.type", "openai");

    processor.postProcessEnvironment(env, application);

    assertThat(env.getPropertySources().contains(ProviderEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    assertThat(env.getProperty("spring.ai.openai.microsoft-foundry")).isNull();
  }

  @Test
  void missingTypeContributesNothing() {
    MockEnvironment env = new MockEnvironment();

    processor.postProcessEnvironment(env, application);

    assertThat(env.getPropertySources().contains(ProviderEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
  }
}
