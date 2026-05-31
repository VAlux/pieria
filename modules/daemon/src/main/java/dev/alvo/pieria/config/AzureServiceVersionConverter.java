package dev.alvo.pieria.config;

import com.openai.azure.AzureOpenAIServiceVersion;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code spring.ai.openai.microsoft-foundry-service-version} property (typed as
 * {@link AzureOpenAIServiceVersion} by Spring AI) from a plain version string such as
 * {@code "2024-10-21"}. The OpenAI Java SDK exposes {@link AzureOpenAIServiceVersion#fromString}
 * rather than a {@code valueOf}/{@code of}/{@code from} factory, so Spring's binder cannot convert
 * the string on its own; this {@link ConfigurationPropertiesBinding} converter supplies the mapping.
 *
 * <p>Only relevant when {@code pieria.provider.type=azure}
 * (see {@link ProviderEnvironmentPostProcessor}); on the OpenAI-compatible path the property is
 * never set.
 */
@Component
@ConfigurationPropertiesBinding
public class AzureServiceVersionConverter implements Converter<String, AzureOpenAIServiceVersion> {

  @Override
  public AzureOpenAIServiceVersion convert(String source) {
    return AzureOpenAIServiceVersion.fromString(source.strip());
  }
}
