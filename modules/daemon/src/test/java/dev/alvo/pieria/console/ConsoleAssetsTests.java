package dev.alvo.pieria.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class ConsoleAssetsTests {

  @Test
  void consoleUsesAccessibleProfileSidePanelBelowHeader() throws IOException {
    Document html = Jsoup.parse(resource("static/index.html"));

    assertThat(html.select(".topbar #profileLabel, .topbar #nav, .topbar #exportBtn")).hasSize(3);
    assertThat(html.select("#profileSelect")).isEmpty();
    assertThat(html.select(".content-shell > #sidePanel + main")).hasSize(1);
    assertThat(html.select("#sidePanel #profilesCategoryTitle")).hasSize(1);
    assertThat(html.select("#sidePanel .side-panel-category[aria-labelledby=profilesCategoryTitle] #profileList"))
      .hasSize(1);
    assertThat(html.select("#sidePanel .side-panel-category-header[title=Profiles] svg")).hasSize(1);
    assertThat(html.select("#sidePanelToggle[aria-controls=sidePanelContent][aria-expanded=true]"))
      .hasSize(1);
    assertThat(html.select("#sidePanelToggle[aria-label='Collapse side panel'] svg")).hasSize(2);
  }

  @Test
  void sidePanelControllerUsesInitialViewportWithoutPersistentStorage() throws IOException {
    String controller = resource("static/js/console/side-panel.js");
    String css = resource("static/css/console.css");

    assertThat(controller)
      .contains("(max-width: 720px)", "window.matchMedia", "aria-expanded", "aria-hidden")
      .doesNotContain("localStorage", "sessionStorage", "addEventListener(\"resize\"");
    assertThat(css)
      .contains("flex: 0 0 240px", "flex-basis: 56px", ".side-panel.is-collapsed .side-panel-list")
      .contains("text-overflow: ellipsis", "overflow-y: auto");
  }

  @Test
  void profileListContractsArePresent() throws IOException {
    String profiles = resource("static/js/console/profiles.js");

    assertThat(profiles)
      .contains("localeCompare", "profile.memoryCount", "aria-current", "Loading profiles…")
      .contains("No profiles", "Profiles unavailable", "loadActiveView(true)");
  }

  @Test
  void graphViewRespondsToResizeAndHandsBackControlWhenItsTabIsLeft() throws IOException {
    String graph = resource("static/js/graph/index.js");
    String router = resource("static/js/console/router.js");

    assertThat(graph)
      .contains("new ResizeObserver", "resizeObserver.observe($(\"view-graph\"))")
      .contains("export function showGraph", "export function hideGraph");
    // The router must actively tear the graph down; relying on CSS alone would leave a force
    // simulation running and pointer state live behind whichever tab took over.
    assertThat(router).contains("hideGraph()");
  }

  @Test
  void graphInteractionStaysInsideItsOwnTab() throws IOException {
    String canvas = resource("static/js/graph/canvas.js");
    Document html = Jsoup.parse(resource("static/index.html"));

    // Pointer handling binds to the canvas and uses pointer capture for drags. A window-level
    // mouse listener is what previously let the graph hit-test — and pop a tooltip — while the
    // user was looking at the Memories tab.
    assertThat(canvas)
      .contains("canvas.addEventListener(\"pointerdown\"", "setPointerCapture")
      .doesNotContain("window.addEventListener");

    // The hover card lives inside the graph view, so `.view { display:none }` hides it with the
    // tab. It must not be a body-level element like the old #graphTooltip.
    assertThat(html.select("#view-graph #graphHoverCard")).hasSize(1);
    assertThat(html.select("body > #graphTooltip")).isEmpty();
    assertThat(html.select("#graphTooltip")).isEmpty();
  }

  @Test
  void graphExplorerOffersTypeFilteringAndAnInspector() throws IOException {
    Document html = Jsoup.parse(resource("static/index.html"));
    String api = resource("static/js/graph/api.js");
    String controls = resource("static/js/graph/controls.js");

    // Type filtering is server-side: toggling refetches with the types param rather than dimming
    // nodes that were already downloaded.
    assertThat(html.select("#view-graph #graphTypeList")).hasSize(1);
    assertThat(html.select("#view-graph #graphTypeAll, #view-graph #graphTypeNone")).hasSize(2);
    assertThat(controls).contains("handlers.onTypesChanged()");
    assertThat(api).contains("types: types");

    // Clicking a node has somewhere to put the answer.
    assertThat(html.select("#view-graph #graphInspector #graphInspectorBody")).hasSize(1);
    assertThat(html.select("#view-graph #graphSearch")).hasSize(1);
    assertThat(html.select("#view-graph #graphFooter")).hasSize(1);

    // Only bounded reads — no whole-graph fetch.
    assertThat(api)
      .contains("/graph/overview", "/graph/neighborhood", "/graph/search", "/graph/entities/")
      .doesNotContain("\"/graph\"");
  }

  @Test
  void graphFilteringDistinguishesNoTypesFromAllTypesAndDiscardsStaleResponses() throws IOException {
    String model = resource("static/js/graph/model.js");
    String index = resource("static/js/graph/index.js");

    // An empty `types` param means "no filter" on the wire, so "user unchecked everything" cannot
    // be represented as an empty list — it has to be handled before the request is made, or the
    // server answers with the entire graph.
    assertThat(model).contains("export function typeFilter", "export function noTypesSelected");
    assertThat(index).contains("noTypesSelected()");

    // Filter toggles fire overlapping requests whose latencies differ by an order of magnitude
    // (~1s unfiltered vs ~50ms narrow), so responses arrive out of order routinely.
    assertThat(index).contains("loadSeq", "isCurrent(seq, profile)");
  }

  @Test
  void vendoredForceLayoutIsSelfContained() throws IOException {
    String d3 = resource("static/js/vendor/d3-force.js");

    // The console is served from the jar and must work offline: the bundle has to inline its own
    // dependencies rather than import them from a CDN at runtime.
    assertThat(d3).doesNotContain("from\"http", "from \"http", "from\"/npm", "from \"/npm");
    assertThat(d3).contains("forceSimulation", "forceManyBody", "forceLink");
  }

  private static String resource(String path) throws IOException {
    try (InputStream stream = ConsoleAssetsTests.class.getClassLoader().getResourceAsStream(path)) {
      assertThat(stream).as("classpath resource %s", path).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
