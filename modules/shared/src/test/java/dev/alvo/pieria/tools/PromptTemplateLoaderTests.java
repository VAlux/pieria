package dev.alvo.pieria.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PromptTemplateLoaderTests {

  @Test
  void rendersPlaceholdersIncludingRepeats() {
    String rendered = PromptTemplateLoader.render("test-template",
      Map.of("name", "Ada", "place", "Pieria"));
    assertThat(rendered).isEqualTo("Hello Ada, welcome to Pieria.\nRepeated: Ada.\n");
  }

  @Test
  void insertsValuesLiterallyWithoutRecursiveExpansion() {
    String rendered = PromptTemplateLoader.render("test-template",
      Map.of("name", "{{place}} & $1 \\ backref", "place", "here"));
    assertThat(rendered).startsWith("Hello {{place}} & $1 \\ backref, welcome to here.");
  }

  @Test
  void missingPlaceholderValueThrows() {
    assertThatIllegalArgumentException()
      .isThrownBy(() -> PromptTemplateLoader.render("test-template", Map.of("name", "Ada")))
      .withMessageContaining("place");
  }

  @Test
  void missingTemplateResourceThrows() {
    assertThatIllegalArgumentException()
      .isThrownBy(() -> PromptTemplateLoader.load("no-such-template"))
      .withMessageContaining("prompts/no-such-template.txt");
  }

  @Test
  void loadReturnsRawTemplateWithPlaceholdersIntact() {
    assertThat(PromptTemplateLoader.load("test-template")).contains("{{name}}", "{{place}}");
  }
}
