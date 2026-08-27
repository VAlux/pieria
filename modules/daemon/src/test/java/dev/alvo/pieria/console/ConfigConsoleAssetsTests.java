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

  @Test
  void globalViewGroupsByTierAndHandsOverTheRestartCommand() throws IOException {
    String global = resource("static/js/console/config/global.js");

    assertThat(global)
      .contains("export function loadGlobalConfig", "export function unloadGlobalConfig")
      .contains("\"/v1/config\"", "\"restart\"", "\"locked\"");
    // There is deliberately no "applies immediately" tier: the daemon binds pieria.properties once
    // at startup and never re-reads it, so no global key takes effect without a restart. Asserting
    // the absence keeps a later edit from quietly reintroducing the claim.
    assertThat(global).doesNotContain("\"live\"");
    // The browser cannot restart the daemon; the page hands over the command the daemon serves
    // rather than offering a button that would not do what it says.
    assertThat(global).contains("restartCommand").doesNotContain("/v1/daemon/restart");
  }

  @Test
  void lockedTierRequiresAnExplicitAcknowledgementOnTheWire() throws IOException {
    String global = resource("static/js/console/config/global.js");

    assertThat(global).contains("acknowledgeDestructive");
    assertThat(global).contains("memories_vec");
  }

  @Test
  void pendingRestartIsReadFromTheServerNotInferredFromLocalEdits() throws IOException {
    String global = resource("static/js/console/config/global.js");

    // The daemon reports file-vs-running divergence, so the banner survives a page reload.
    assertThat(global).contains("restart-pending");
  }

  @Test
  void globalConfigViewSectionExistsInTheShell() throws IOException {
    Document html = Jsoup.parse(resource("static/index.html"));

    assertThat(html.select("main > section.view#view-global-config")).hasSize(1);
  }

  @Test
  void bothConfigEntriesLiveInTheSidePanelNotTheNavBar() throws IOException {
    Document html = Jsoup.parse(resource("static/index.html"));

    // Global config hangs off the daemon block, because that is its scope.
    assertThat(html.select("#sidePanel #daemonConfigLink[data-view=global-config]")).hasSize(1);
    // A seventh nav tab would read as global and undercut the per-profile scoping.
    assertThat(html.select(".nav button[data-view=profile-config]")).isEmpty();
    assertThat(html.select(".nav button[data-view=global-config]")).isEmpty();
  }

  @Test
  void selectingAProfileRevealsItsConfigurationEntry() throws IOException {
    String profiles = resource("static/js/console/profiles.js");
    String css = resource("static/css/console.css");

    // Call-site shape, not bare token presence: renderSubList(row) is the actual call (built once,
    // called from both renderProfiles and markSelected), and the dataset assignment is what makes
    // the rendered entry route to profile-config. A stray comment mentioning either word could not
    // satisfy this.
    assertThat(profiles).contains("renderSubList(row)", "link.dataset.view = \"profile-config\";");
    assertThat(css).contains(".side-panel-subitem");
  }

  @Test
  void routerLoadsAndTearsDownBothConfigViews() throws IOException {
    String router = resource("static/js/console/router.js");
    String main = resource("static/js/console/main.js");

    assertThat(router)
      .contains("profile-config", "global-config")
      .contains("loadProfileConfig", "loadGlobalConfig")
      // Both views hold fetched state; leaving must drop it so a profile switch cannot show the
      // previous profile's overrides. Asserting the call site itself ("();" included), not just the
      // bare name, so a comment mentioning these functions could not satisfy this.
      .contains("unloadProfileConfig();", "unloadGlobalConfig();");
    assertThat(main).contains("\"profile-config\"", "\"global-config\"");
  }

  @Test
  void anEmptyProfileStoreStillLoadsTheDaemonConfigView() throws IOException {
    String profiles = resource("static/js/console/profiles.js");
    String router = resource("static/js/console/router.js");

    // The no-profiles branch returns before selectProfile, so without this call loadActiveView
    // never runs and the daemon config page is unreachable on a fresh, empty store.
    assertThat(profiles).contains("No profiles", "loadActiveView(false)");
    // And within loadActiveView, global-config must be dispatched BEFORE the profile guard, or
    // the call above still falls through to nothing. Scope the search to that function's body:
    // "global-config" also occurs earlier in setView's teardown guard, so searching the whole
    // file passes even with the branches reordered — an assertion that pins nothing.
    int start = router.indexOf("export function loadActiveView");
    assertThat(start).as("loadActiveView must exist in router.js").isNotNegative();
    String body = router.substring(start);
    int dispatch = body.indexOf("global-config");
    int guard = body.indexOf("if (!state.profile) return;");
    assertThat(dispatch).as("loadActiveView must dispatch global-config").isNotNegative();
    assertThat(guard).as("loadActiveView must keep its profile guard").isNotNegative();
    assertThat(dispatch).as("global-config must come before the profile guard").isLessThan(guard);
  }

  static String resource(String path) throws IOException {
    try (InputStream in = ConfigConsoleAssetsTests.class.getClassLoader().getResourceAsStream(path)) {
      assertThat(in).as("resource %s", path).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
