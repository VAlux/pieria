package dev.alvo.pieria.harvesters.jira;

import dev.alvo.pieria.harvester.conformance.ConformanceKit;
import dev.alvo.pieria.harvester.conformance.ConformanceOptions;
import dev.alvo.pieria.harvester.conformance.ConformanceReport;
import dev.alvo.pieria.harvester.conformance.ConformanceTarget;
import dev.alvo.pieria.harvester.manifest.ManifestParser;
import dev.alvo.pieria.harvesters.PieriaHarvest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JiraConformanceTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void passesTheConformanceKit(boolean cloud) {
    try (StubJira jira = new StubJira(cloud)) {
      jira.add("10001", "T-1", "One", T, T.plusSeconds(60), "<p>first</p>").comment("1", "Bo", T.plusSeconds(70), "<p>hi</p>");
      jira.add("10002", "T-2", "Two", T.plusSeconds(1), T.plusSeconds(61), "<p>second</p>");
      jira.add("10003", "T-3", "Three", T.plusSeconds(2), T.plusSeconds(62), "<p>third</p>");
      ObjectNode config = MAPPER.createObjectNode().put("baseUrl", jira.baseUrl()).put("jql", "project = T")
        .put("deployment", cloud ? "cloud" : "datacenter");
      if (cloud) {
        config.put("email", StubJira.EMAIL);
      }
      ObjectNode invalid = config.deepCopy();
      invalid.remove("jql");
      ConformanceTarget target = new ConformanceTarget(ManifestParser.load(Path.of("manifests/jira/harvester.toml")),
        List.of(ProcessHandle.current().info().command().orElseThrow(), "-cp", System.getProperty("java.class.path"),
          PieriaHarvest.class.getName(), "jira"),
        Path.of(".").toAbsolutePath());

      ConformanceReport report = ConformanceKit.run(target,
        new ConformanceOptions(config, Map.of("token", StubJira.TOKEN), invalid, Duration.ofSeconds(60)));

      assertThat(report.passed()).as(report.render()).isTrue();
    }
  }
}
