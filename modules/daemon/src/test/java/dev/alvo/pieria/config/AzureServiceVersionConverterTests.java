package dev.alvo.pieria.config;

import com.openai.azure.AzureOpenAIServiceVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the dashed Azure API version string binds to the OpenAI SDK's
 * {@link AzureOpenAIServiceVersion} via {@code fromString}.
 */
class AzureServiceVersionConverterTests {

  private final AzureServiceVersionConverter converter = new AzureServiceVersionConverter();

  @Test
  void convertsDashedWireVersionToServiceVersion() {
    assertThat(converter.convert("2024-10-21")).isEqualTo(AzureOpenAIServiceVersion.getV2024_10_21());
  }

  @Test
  void trimsWhitespace() {
    assertThat(converter.convert("  2024-10-21  ")).isEqualTo(AzureOpenAIServiceVersion.getV2024_10_21());
  }
}
