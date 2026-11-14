package dev.alvo.pieria.harvesters.confluence;

import dev.alvo.pieria.harvester.conformance.ConformanceKit;
import dev.alvo.pieria.harvester.conformance.ConformanceOptions;
import dev.alvo.pieria.harvester.conformance.ConformanceReport;
import dev.alvo.pieria.harvester.conformance.ConformanceTarget;
import dev.alvo.pieria.harvester.manifest.ManifestParser;
import dev.alvo.pieria.harvesters.PieriaHarvest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfluenceConformanceTests {

  private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void passesTheConformanceKit(boolean cloud) {
    try (StubConfluence confluence = new StubConfluence(cloud)) {
      confluence.add("101", "OPS", "Runbook", T, "<h1>Restart</h1><p>Restart the worker.</p>");
      confluence.add("102", "OPS", "Oncall", T.plusSeconds(1), "<p>Page the lead.</p>");
      confluence.add("103", "OPS", "Deploys", T.plusSeconds(2), "<p>Deploy on Tuesdays.</p>");
      ObjectNode config = ConfluenceFeedTests.config(confluence);
      ObjectNode invalid = config.deepCopy();
      invalid.remove("spaces");
      ConformanceTarget target = new ConformanceTarget(ManifestParser.load(Path.of("manifests/confluence/harvester.toml")),
        List.of(ProcessHandle.current().info().command().orElseThrow(), "-cp", System.getProperty("java.class.path"),
          PieriaHarvest.class.getName(), "confluence"),
        Path.of(".").toAbsolutePath());

      ConformanceReport report = ConformanceKit.run(target,
        new ConformanceOptions(config, Map.of("token", StubConfluence.TOKEN), invalid, Duration.ofSeconds(60)));

      assertThat(report.passed()).as(report.render()).isTrue();
    }
  }
}
