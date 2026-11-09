package dev.alvo.pieria.harvesters.html;

import dev.alvo.pieria.harvester.conformance.ConformanceKit;
import dev.alvo.pieria.harvester.conformance.ConformanceOptions;
import dev.alvo.pieria.harvester.conformance.ConformanceReport;
import dev.alvo.pieria.harvester.conformance.ConformanceTarget;
import dev.alvo.pieria.harvester.manifest.ManifestParser;
import dev.alvo.pieria.harvesters.PieriaHarvest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlConformanceTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void passesTheConformanceKit() {
    try (StubSite site = StubSite.start()) {
      // A dated site, because the kit compares whole records between runs and an undated page is stamped "now".
      Map<String, String> dated = Map.of("Content-Type", "text/html", "Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT");
      site.respond("/", 200, dated, "<h1>Home</h1><a href=\"/a\">a</a><a href=\"/b\">b</a>");
      site.respond("/a", 200, dated, "<h1>A</h1><p>Alpha</p>");
      site.respond("/b", 200, dated, "<h1>B</h1><p>Beta</p>");
      ObjectNode config = MAPPER.createObjectNode().put("allowPrivate", true).put("delayMillis", 0);
      config.putArray("seeds").add(site.baseUrl() + "/");
      ObjectNode invalid = MAPPER.createObjectNode();
      invalid.putArray("seeds").add("not a url");
      ConformanceTarget target = new ConformanceTarget(ManifestParser.load(Path.of("manifests/html/harvester.toml")),
        List.of(ProcessHandle.current().info().command().orElseThrow(), "-cp", System.getProperty("java.class.path"),
          PieriaHarvest.class.getName(), "html"),
        Path.of(".").toAbsolutePath());

      ConformanceReport report = ConformanceKit.run(target, new ConformanceOptions(config, Map.of(), invalid, Duration.ofSeconds(60)));

      assertThat(report.passed()).as(report.render()).isTrue();
    }
  }
}
