package dev.alvo.pieria.model;

import dev.alvo.pieria.tools.PromptTemplateLoader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the wiring between {@link OpenAiModelGateway} call sites and the prompt template
 * resources under {@code prompts/}: every template name the gateway renders must resolve on the
 * classpath and be non-blank. A renamed or missing resource fails here instead of at runtime.
 */
class PromptTemplatesTests {

  @ParameterizedTest
  @ValueSource(strings = {
    "extract-unified",
    "verify-single",
    "verify-batch",
    "classify-single",
    "classify-batch",
    "extract-graph-single",
    "extract-graph-batch",
    "analyze-query",
    "synthesize-recall",
    "summarize-code-file",
    "summarize-code-module",
    "summarize-code-architecture",
    "judge-answer-faithfulness"
  })
  void templateResolvesAndIsNonBlank(String name) {
    assertThat(PromptTemplateLoader.load(name)).isNotBlank();
  }
}
