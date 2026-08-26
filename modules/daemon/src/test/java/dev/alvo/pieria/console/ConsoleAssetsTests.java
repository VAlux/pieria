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
  void daemonStatusSitsBelowTheProfileListAndReportsVectorSearch() throws IOException {
    Document html = Jsoup.parse(resource("static/index.html"));
    String daemon = resource("static/js/console/daemon.js");
    String css = resource("static/css/console.css");

    // The block is a sibling of the scroll area, not a category inside it, so it stays pinned to
    // the foot of the panel while the profile list scrolls.
    assertThat(html.select("#sidePanel > #daemonStatus")).hasSize(1);
    assertThat(html.select("#daemonStatus #daemonDot, #daemonStatus #daemonLabel, #daemonStatus #daemonRows"))
      .hasSize(3);

    // vectorSearch is the one signal distinguishing a working daemon from a quietly reduced one:
    // retrieval falls back to FTS-only when sqlite-vec does not load, silently.
    assertThat(daemon)
      .contains("/pieria-health", "/pieria-status", "status.vectorSearch", "FTS only")
      .contains("vectorizationOutboxDepth");

    // Collapsed, the block reduces to the status dot alone rather than clipping mid-row.
    assertThat(css).contains(".side-panel.is-collapsed .daemon-rows");
  }

  @Test
  void taskTrayRendersLaneProgressAndCanCancel() throws IOException {
    Document html = Jsoup.parse(resource("static/index.html"));
    String tasks = resource("static/js/console/tasks.js");

    assertThat(html.select(".topbar .tray #trayBtn[aria-controls=trayPanel]")).hasSize(1);
    assertThat(html.select(".topbar .tray #trayPanel #trayList")).hasSize(1);

    // Lane done/total is what makes a real progress bar possible; a task with no lanes still has
    // to render, so the tray reads them defensively.
    assertThat(tasks)
      .contains("/v1/tasks", "lane.done", "lane.total", "t.lanes || []")
      .contains("\"DELETE\"", "startedAtEpochMs");

    // Polling backs off when nothing is running — the tray is a status light, not a feed.
    assertThat(tasks).contains("IDLE_POLL_MS", "running().length");
  }

  @Test
  void recallAsksForDebugAndExplainsWhereTheTimeWent() throws IOException {
    String recall = resource("static/js/console/recall.js");
    String palette = resource("static/js/util/palette.js");

    assertThat(recall).contains("debug: true");

    // Channels run in parallel, so their share of wall clock is the slowest one rather than the
    // sum — and RecallDebug itemises only the channels, so the rest is measured client-side.
    assertThat(recall)
      .contains("Math.max(slowest", "performance.now()")
      .contains("query analysis + HyDE + synthesis");

    // `source` is "rrf:<channel>[+<channel>...]"; the constant prefix carries no information.
    assertThat(recall).contains("replace(/^rrf:/", "split(\"+\")");

    // Channel colours are keyed by the wire form RecallDebug reports, so a channel keeps one
    // colour across the diagnostics cards and the provenance chips.
    assertThat(palette)
      .contains("fts_memory", "exact_key", "fts_message", "direct_vector")
      .contains("hyde_vector", "symbol_fts", "code_graph");
  }

  @Test
  void memoryRowsCarryATypeRailAndASeparateTimeColumn() throws IOException {
    String memories = resource("static/js/console/memories.js");
    String css = resource("static/css/console.css");

    // One row builder, shared with the recall view, so the two lists cannot drift apart.
    assertThat(memories).contains("export function memoryRow", "mem-rail", "mem-time");
    assertThat(resource("static/js/console/recall.js")).contains("memoryRow(m, false)");

    // Two lines rather than one hard truncate, and the chip is a tint rather than a flood.
    assertThat(css).contains("-webkit-line-clamp: 2", ".mem-rail");
    assertThat(resource("static/js/util/palette.js")).contains("export function typeTint");
  }

  @Test
  void consoleDrawsIconsAsInlineSvgRatherThanTextGlyphs() throws IOException {
    String html = resource("static/index.html");
    String memories = resource("static/js/console/memories.js");
    String dom = resource("static/js/util/dom.js");

    assertThat(dom).contains("export function icon");
    // Text glyphs rendered in three unrelated typefaces and shifted baseline per platform.
    assertThat(html).doesNotContain("⬇", "🗑");
    assertThat(memories).doesNotContain("🗑");
    assertThat(memories).contains("icon(\"trash\"");
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
