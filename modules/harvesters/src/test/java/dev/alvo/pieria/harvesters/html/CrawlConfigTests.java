package dev.alvo.pieria.harvesters.html;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrawlConfigTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ObjectNode withSeed() {
    ObjectNode config = MAPPER.createObjectNode();
    config.putArray("seeds").add("https://x.test/");
    return config;
  }

  private static void assertRejected(ObjectNode config, String mention) {
    assertThatThrownBy(() -> CrawlConfig.parse(config))
      .isInstanceOfSatisfying(HarvesterException.class, e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.CONFIG))
      .hasMessageContaining(mention);
  }

  @Test
  void appliesDefaults() throws Exception {
    CrawlConfig config = CrawlConfig.parse(withSeed());

    assertThat(config.maxDepth()).isEqualTo(3);
    assertThat(config.maxPages()).isEqualTo(500);
    assertThat(config.delayMillis()).isEqualTo(1000);
    assertThat(config.allowPrivate()).isFalse();
    assertThat(config.allowedHosts()).containsExactly("x.test");
    assertThat(config.seeds()).containsExactly("https://x.test/");
  }

  @Test
  void allowedHostsAlsoComeFromSitemaps() throws Exception {
    ObjectNode config = MAPPER.createObjectNode();
    config.putArray("sitemaps").add("https://maps.test/sitemap.xml");

    assertThat(CrawlConfig.parse(config).allowedHosts()).containsExactly("maps.test");
  }

  @Test
  void explicitAllowedHostsAreLowercased() throws Exception {
    ObjectNode config = withSeed();
    config.putArray("allowedHosts").add("X.TEST").add("*.Example.COM");

    assertThat(CrawlConfig.parse(config).allowedHosts()).containsExactlyInAnyOrder("x.test", "*.example.com");
  }

  @Test
  void requiresSeedsOrSitemaps() {
    assertRejected(MAPPER.createObjectNode(), "seeds");
  }

  @ParameterizedTest
  @CsvSource({"maxPages,0", "maxPages,2001", "maxDepth,51", "maxDepth,-1", "delayMillis,-1", "delayMillis,60001"})
  void rejectsOutOfRange(String key, int value) {
    assertRejected(withSeed().put(key, value), key);
  }

  @Test
  void rejectsAWrongType() {
    assertRejected(withSeed().put("maxPages", "many"), "maxPages");
  }

  @Test
  void rejectsAWrongListType() {
    assertRejected(withSeed().put("exclude", "x"), "exclude");
  }

  @Test
  void rejectsAWrongBooleanType() {
    assertRejected(withSeed().put("allowPrivate", "yes"), "allowPrivate");
  }

  @Test
  void rejectsABadRegexNamingIt() {
    ObjectNode config = withSeed();
    config.putArray("include").add("[unclosed");

    assertRejected(config, "[unclosed");
  }

  @Test
  void rejectsASeedOutsideTheScope() {
    ObjectNode config = withSeed();
    config.putArray("allowedHosts").add("other.test");

    assertRejected(config, "https://x.test/");
  }

  @Test
  void rejectsASeedTheExcludesDrop() {
    ObjectNode config = withSeed();
    config.putArray("exclude").add("x\\.test");

    assertRejected(config, "https://x.test/");
  }

  @Test
  void rejectsAnAllowedHostWrittenAsAUrl() {
    ObjectNode config = withSeed();
    config.putArray("allowedHosts").add("https://x.test");

    assertRejected(config, "https://x.test");
  }

  @Test
  void rejectsABadSeed() {
    ObjectNode config = MAPPER.createObjectNode();
    config.putArray("seeds").add("not a url");

    assertRejected(config, "not a url");
  }
}
