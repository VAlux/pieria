package dev.alvo.pieria.harvesters;

import dev.alvo.pieria.harvester.HarvesterRunner;
import dev.alvo.pieria.harvester.manifest.Capability;
import dev.alvo.pieria.harvester.manifest.HarvesterManifest;
import dev.alvo.pieria.harvester.manifest.ManifestParser;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.RequestCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PieriaHarvestTests {

  private record Outcome(int code, String out, String err) {
  }

  private static Outcome run(String... args) {
    String envelope = RequestCodec.encode(new HarvestRequest(1, new ObjectMapper().createObjectNode(), Map.of(), null, null)) + "\n";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = PieriaHarvest.run(args, new ByteArrayInputStream(envelope.getBytes(StandardCharsets.UTF_8)), out, new PrintStream(err));
    return new Outcome(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void unknownHarvesterIsAUsageError() {
    Outcome outcome = run("nope", "check");

    assertThat(outcome.code()).isEqualTo(HarvesterRunner.EXIT_USAGE);
    assertThat(outcome.err()).contains("html");
  }

  @Test
  void wrongArgumentCountIsAUsageError() {
    assertThat(run("html").code()).isEqualTo(HarvesterRunner.EXIT_USAGE);
  }

  @Test
  void dispatchesToTheNamedHarvester() {
    Outcome outcome = run("html", "check");

    assertThat(outcome.code()).isEqualTo(HarvesterRunner.EXIT_FAILED);
    assertThat(outcome.out()).contains("\"type\":\"error\"");
    assertThat(outcome.err()).doesNotContain("usage");
  }

  @Test
  void shippedManifestParses() {
    HarvesterManifest manifest = ManifestParser.load(Path.of("manifests/html/harvester.toml"));

    assertThat(manifest.id()).isEqualTo("html");
    assertThat(manifest.command()).isEqualTo(List.of("./pieria-harvest", "html"));
    assertThat(manifest.capabilities()).containsExactlyInAnyOrder(Capability.INCREMENTAL, Capability.DELETES);
  }
}
