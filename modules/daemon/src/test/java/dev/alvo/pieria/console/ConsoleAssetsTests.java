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
  void profileListAndGraphResizeContractsArePresent() throws IOException {
    String profiles = resource("static/js/console/profiles.js");
    String graph = resource("static/js/graph/index.js");

    assertThat(profiles)
      .contains("localeCompare", "profile.memoryCount", "aria-current", "Loading profiles…")
      .contains("No profiles", "Profiles unavailable", "loadActiveView(true)");
    assertThat(graph)
      .contains("new ResizeObserver", "graphResizeObserver.observe($(\"view-graph\"))")
      .contains("resize(); draw();");
  }

  private static String resource(String path) throws IOException {
    try (InputStream stream = ConsoleAssetsTests.class.getClassLoader().getResourceAsStream(path)) {
      assertThat(stream).as("classpath resource %s", path).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
