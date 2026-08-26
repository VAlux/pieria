package dev.alvo.pieria.console;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the configuration console assets. There is no JS runner in this repo, so the
 * console's behavioural contracts are pinned here the same way the rest of the console is.
 */
class ConfigConsoleAssetsTests {

  @Test
  void configStylesheetIsLinkedAndReusesTheExistingTokens() throws IOException {
    Document html = Jsoup.parse(resource("static/index.html"));
    String css = resource("static/css/config.css");

    assertThat(html.select("link[href=css/config.css]")).hasSize(1);
    // No new colour tokens: the config pages ride base.css.
    assertThat(css).doesNotContain(":root {");
    assertThat(css).contains("var(--accent)", "var(--panel)", "var(--border)", "var(--dim)");
    assertThat(css).contains(".cfg-row", ".cfg-chip", ".cfg-savebar");
  }

  @Test
  void schemaModuleCachesOneFetchAndGroupsBySection() throws IOException {
    String schema = resource("static/js/console/config/schema.js");

    assertThat(schema)
      .contains("/v1/config/schema", "export function loadSchema", "export function bySection")
      .contains("cached");
    // One schema fetch serves both pages; re-fetching per view would be a needless round trip.
    assertThat(schema).doesNotContain("localStorage", "sessionStorage");
  }

  @Test
  void fieldRendererCoversEveryControlKindTheSchemaCanDeclare() throws IOException {
    String field = resource("static/js/console/config/field.js");

    assertThat(field).contains("export function renderFieldRow");
    // Every kind in config-schema.json must have a branch, or a field renders as nothing.
    assertThat(field).contains("\"weight\"", "\"int\"", "\"double\"", "\"bool\"", "\"enum\"",
      "\"string\"", "\"secret\"");
    assertThat(field).contains("cfg-row", "cfg-dot", "cfg-key", "cfg-chip", "cfg-reset");
  }

  @Test
  void everySchemaKindHasARendererBranch() throws IOException {
    String schemaJson = resource("config/config-schema.json");
    String field = resource("static/js/console/config/field.js");

    for (String kind : new String[] {"weight", "int", "double", "bool", "enum", "string", "secret"}) {
      if (schemaJson.contains("\"kind\":\"" + kind + "\"")) {
        assertThat(field)
          .as("field.js must handle kind '%s' declared in config-schema.json", kind)
          .contains("\"" + kind + "\"");
      }
    }
  }

  static String resource(String path) throws IOException {
    try (InputStream in = ConfigConsoleAssetsTests.class.getClassLoader().getResourceAsStream(path)) {
      assertThat(in).as("resource %s", path).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
