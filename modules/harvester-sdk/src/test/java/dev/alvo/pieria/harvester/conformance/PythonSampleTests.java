package dev.alvo.pieria.harvester.conformance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample Python harvester in {@code harvest-examples/notes}: its own tests, then the conformance
 * kit, exactly as an outside author would run it. Skipped where {@code python3} isn't on PATH.
 */
@EnabledIf("pythonAvailable")
class PythonSampleTests {

  /** Gradle runs a module's tests from the module directory. */
  private static final Path SAMPLE = Path.of("../../harvest-examples/notes").toAbsolutePath().normalize();
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static boolean pythonAvailable() {
    try {
      Process process = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
      return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Test
  void sampleUnitTestsPass() throws Exception {
    Process process = new ProcessBuilder("python3", "-m", "unittest", "discover", "-s", SAMPLE.toString())
      .redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
  }

  @Test
  void samplePassesTheConformanceKit(@TempDir Path notes) throws Exception {
    String body = "Some text about the topic. ".repeat(75);
    for (int i = 0; i < 60; i++) {
      Files.writeString(notes.resolve("note-%02d.md".formatted(i)), "# Note " + i + "\n" + body);
    }
    ObjectNode config = MAPPER.createObjectNode().put("directory", notes.toString());
    ObjectNode invalid = MAPPER.createObjectNode().put("directory", notes.resolve("missing").toString());

    ConformanceReport report = ConformanceKit.run(ConformanceTarget.fromManifest(SAMPLE.resolve("harvester.toml")),
      new ConformanceOptions(config, Map.of(), invalid, Duration.ofSeconds(60)));

    assertThat(report.passed()).as(report.render()).isTrue();
    List<ConformanceReport.Result> results = report.results();
    assertThat(results).as(report.render())
      .anySatisfy(result -> {
        assertThat(result.check()).isEqualTo("exits-when-stdout-closes");
        assertThat(result.status()).isEqualTo(ConformanceReport.Status.PASS);
      })
      .anySatisfy(result -> {
        assertThat(result.check()).isEqualTo("sweep-is-complete");
        assertThat(result.status()).isEqualTo(ConformanceReport.Status.PASS);
      })
      .anySatisfy(result -> {
        assertThat(result.check()).isEqualTo("check-rejects-invalid-config");
        assertThat(result.status()).isEqualTo(ConformanceReport.Status.PASS);
      });
  }
}
