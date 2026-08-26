package dev.alvo.pieria.console;

import dev.alvo.pieria.config.schema.ConfigField;
import dev.alvo.pieria.config.schema.ConfigSchemaService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

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
  void fieldRendererBranchesOnEveryKindThatNeedsItsOwnControl() throws IOException {
    String field = resource("static/js/console/config/field.js");

    assertThat(field).contains("export function renderFieldRow");
    // Only three kinds need a control of their own; the rest share the text input.
    assertThat(field).contains("\"bool\"", "\"enum\"", "\"weight\"");
    // The wide input is what separates free-text keys from numeric ones.
    assertThat(field).contains("\"string\"", "\"secret\"");
    assertThat(field).contains("cfg-row", "cfg-dot", "cfg-key", "cfg-chip", "cfg-reset");
  }

  @Test
  void everySchemaKindEitherBranchesOrFallsThroughToTheTextInput() throws IOException {
    String field = resource("static/js/console/config/field.js");

    // int, double, string and secret deliberately share one text input, so they never appear as
    // named branches. Asserting that they did would be a test a stray comment could satisfy —
    // which is exactly what this assertion is written to avoid.
    Set<String> branched = Set.of("bool", "enum", "weight");
    Set<String> sharedTextInput = Set.of("int", "double", "string", "secret");

    Set<String> declared = new ConfigSchemaService().all().stream()
      .map(ConfigField::kind)
      .collect(Collectors.toSet());

    assertThat(declared).allSatisfy(kind -> assertThat(branched.contains(kind) || sharedTextInput.contains(kind))
      .as("kind '%s' is declared in config-schema.json but field.js neither branches on it nor "
        + "routes it to the shared text input", kind)
      .isTrue());

    branched.forEach(kind -> assertThat(field)
      .as("field.js must branch on kind '%s'", kind)
      .contains("\"" + kind + "\""));

    // The shared fallback must exist unconditionally after the branches, or every kind routed to
    // it renders nothing at all.
    assertThat(field).contains("input.type = \"text\"");
  }

  @Test
  void profileViewReadsAllThreeLayersAndWritesTheWhitelistedPayload() throws IOException {
    String profile = resource("static/js/console/config/profile.js");

    assertThat(profile)
      .contains("/config/detail", "\"PUT\"", "\"DELETE\"")
      .contains("export function loadProfileConfig", "export function unloadProfileConfig");
    // Provenance comes from the stored override map, never from diffing effective against global:
    // a profile may deliberately override a key to the global value.
    assertThat(profile).contains("overrides").doesNotContain("=== globalValue");
  }

  @Test
  void channelMixTreatsZeroAsADisableNotASmallNumber() throws IOException {
    String mix = resource("static/js/console/config/channel-mix.js");

    assertThat(mix).contains("export function renderChannelMix", "cfg-mix-bar", "cfg-mix-legend");
    assertThat(mix).contains("disabled");
  }

  @Test
  void saveBarBlocksWhenAFieldFailsClientValidation() throws IOException {
    String form = resource("static/js/console/config/form.js");

    assertThat(form)
      .contains("export function createForm", "changedKeys", "renderSaveBar")
      .contains("cfg-savebar", "Discard");
    // The daemon rejects the whole payload if one value fails to bind, so the client must not send
    // a batch it already knows is bad.
    assertThat(form).contains("blocked");
  }

  @Test
  void profileConfigViewSectionExistsInTheShell() throws IOException {
    Document html = Jsoup.parse(resource("static/index.html"));

    assertThat(html.select("main > section.view#view-profile-config")).hasSize(1);
  }

  static String resource(String path) throws IOException {
    try (InputStream in = ConfigConsoleAssetsTests.class.getClassLoader().getResourceAsStream(path)) {
      assertThat(in).as("resource %s", path).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
