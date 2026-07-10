package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.config.model.DiscoveryConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link SourceSpec} wire contract is polymorphic: the {@code type} discriminator must round-trip
 * so a client can send any source kind and the daemon deserializes it to the right subtype.
 */
class SourceSpecJsonTests {

  private final ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void markdownRoundTripsWithTypeDiscriminator() {
    SourceSpec spec = new SourceSpec.Markdown("/abs/proj", true, 3, Boolean.TRUE);

    String json = mapper.writeValueAsString(spec);
    assertThat(json).contains("\"type\":\"markdown\"");

    assertThat(mapper.readValue(json, SourceSpec.class)).isEqualTo(spec);
  }

  @Test
  void sourceCodeRoundTripsWithDiscoveryConfig() {
    SourceSpec spec = new SourceSpec.SourceCode("/abs/proj", true, Boolean.TRUE,
      new DiscoveryConfig(java.util.Set.of("sql"), null, null, null));

    String json = mapper.writeValueAsString(spec);
    assertThat(json).contains("\"type\":\"source-code\"").contains("sql");

    SourceSpec parsed = mapper.readValue(json, SourceSpec.class);
    assertThat(parsed).isInstanceOf(SourceSpec.SourceCode.class);
    assertThat(((SourceSpec.SourceCode) parsed).discovery().sourceExtensions()).containsExactly("sql");
  }

  @Test
  void webRoundTripsWithUrls() {
    SourceSpec spec = new SourceSpec.Web(List.of("https://example.com/a", "https://example.com/b"), null, null);

    String json = mapper.writeValueAsString(spec);
    assertThat(json).contains("\"type\":\"web\"");

    assertThat(mapper.readValue(json, SourceSpec.class)).isEqualTo(spec);
  }

  @Test
  void pdfRoundTripsWithTypeDiscriminator() {
    SourceSpec spec = new SourceSpec.Pdf("/abs/proj", 2, null);

    String json = mapper.writeValueAsString(spec);
    assertThat(json).contains("\"type\":\"pdf\"");

    assertThat(mapper.readValue(json, SourceSpec.class)).isEqualTo(spec);
  }

  @Test
  void textRoundTripsWithTypeDiscriminator() {
    SourceSpec spec = new SourceSpec.Text("/abs/proj", 2, null);

    String json = mapper.writeValueAsString(spec);
    assertThat(json).contains("\"type\":\"text\"");

    assertThat(mapper.readValue(json, SourceSpec.class)).isEqualTo(spec);
  }
}
