package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.conformance.ConformanceKit;
import dev.alvo.pieria.harvester.conformance.ConformanceOptions;
import dev.alvo.pieria.harvester.conformance.ConformanceReport;
import dev.alvo.pieria.harvester.conformance.ConformanceTarget;
import dev.alvo.pieria.harvester.manifest.ManifestParser;
import dev.alvo.pieria.harvesters.PieriaHarvest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TeamsConformanceTests {

  @Test
  void passesTheConformanceKit() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    try (StubMicrosoft microsoft = new StubMicrosoft()) {
      StubMicrosoft.Chat chat = microsoft.chat("c1", "group", "Release");
      chat.message("1", "Ada", today.minusDays(3).atTime(LocalTime.of(9, 0)).toInstant(ZoneOffset.UTC), "<p>plan the release</p>");
      chat.message("2", "Bo", today.minusDays(2).atTime(LocalTime.of(9, 0)).toInstant(ZoneOffset.UTC), "<p>release moved to friday</p>");
      StubMicrosoft.Channel ops = microsoft.team("t1", "Eng").channel("c1", "Ops");
      ops.post("100", "Ada", today.minusDays(1).atTime(LocalTime.of(9, 0)).toInstant(ZoneOffset.UTC), "<p>the api is down</p>");
      ops.reply("100", "101", "Bo", today.minusDays(1).atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC), "<p>fixed it</p>");
      ObjectNode config = microsoft.config();
      config.putArray("channels").add("Eng/Ops");
      ObjectNode invalid = config.deepCopy().put("clientId", "abc");
      ConformanceTarget target = new ConformanceTarget(ManifestParser.load(Path.of("manifests/teams/harvester.toml")),
        List.of(ProcessHandle.current().info().command().orElseThrow(), "-cp", System.getProperty("java.class.path"),
          PieriaHarvest.class.getName(), "teams"),
        Path.of(".").toAbsolutePath());

      ConformanceReport report = ConformanceKit.run(target,
        new ConformanceOptions(config, Map.of("refreshToken", "r1"), invalid, Duration.ofSeconds(60)));

      assertThat(report.passed()).as(report.render()).isTrue();
    }
  }
}
