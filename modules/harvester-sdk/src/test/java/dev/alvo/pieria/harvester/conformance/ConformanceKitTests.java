package dev.alvo.pieria.harvester.conformance;

import dev.alvo.pieria.harvester.manifest.Capability;
import dev.alvo.pieria.harvester.manifest.HarvesterManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.alvo.pieria.harvester.conformance.ConformanceReport.Status.FAIL;
import static dev.alvo.pieria.harvester.conformance.ConformanceReport.Status.SKIP;
import static org.assertj.core.api.Assertions.assertThat;

class ConformanceKitTests {

  private static final String SECRET = "s3cr3t-value-for-tests";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ObjectNode CONFIG = MAPPER.createObjectNode().put("valid", true);
  private static final ObjectNode INVALID_CONFIG = MAPPER.createObjectNode().put("valid", false);

  private static HarvesterManifest manifest(Capability... capabilities) {
    return new HarvesterManifest("fixture", "Fixture", "1", 1, List.of("unused"), "unused", Set.of(capabilities), List.of(), List.of());
  }

  private static final HarvesterManifest FULL = manifest(Capability.INCREMENTAL, Capability.DELETES);

  private static List<String> javaCommand(String mode) {
    return List.of(ProcessHandle.current().info().command().orElseThrow(), "-cp", System.getProperty("java.class.path"),
      FixtureHarvester.class.getName(), mode);
  }

  private static ConformanceReport run(String mode, HarvesterManifest manifest, ObjectNode invalid, Duration timeout) {
    ConformanceTarget target = new ConformanceTarget(manifest, javaCommand(mode), Path.of(".").toAbsolutePath());
    return ConformanceKit.run(target, new ConformanceOptions(CONFIG, Map.of("token", SECRET), invalid, timeout));
  }

  private static ConformanceReport runMode(String mode) {
    HarvesterManifest manifest = mode.equals("undeclared-tombstone") ? manifest(Capability.INCREMENTAL) : FULL;
    return run(mode, manifest, INVALID_CONFIG, Duration.ofSeconds(30));
  }

  private static ConformanceReport.Status status(ConformanceReport report, String check) {
    return report.results().stream().filter(r -> r.check().equals(check)).findFirst().orElseThrow().status();
  }

  @Test
  void correctHarvesterPasses() {
    ConformanceReport report = runMode("ok");

    assertThat(report.passed()).as(report.render()).isTrue();
    assertThat(report.results()).extracting(ConformanceReport.Result::check).containsExactly(
      "check-accepts-valid-config", "check-rejects-invalid-config", "harvest-completes", "secrets-stay-private",
      "harvest-emits-items",
      "capabilities-declared", "ids-stable-across-runs", "resume-loses-nothing", "sweep-is-complete",
      "limit-respected", "exits-when-stdout-closes");
    assertThat(report.results()).filteredOn(r -> !r.check().equals("exits-when-stdout-closes"))
      .allMatch(r -> r.status() == ConformanceReport.Status.PASS, report.render());
  }

  @Test
  void printingToStdoutDoesNotBreakTheProtocol() {
    ConformanceReport report = runMode("noisy");

    assertThat(report.passed()).as(report.render()).isTrue();
  }

  @Test
  void aCompletePassFromACursorMaySweep() {
    ConformanceReport report = runMode("full-pass-on-resume");

    assertThat(report.passed()).as(report.render()).isTrue();
  }

  @Test
  void aHarvesterThatOutlastsItsFirstRecordProvesItExits() {
    ConformanceReport report = runMode("slow-ok");

    assertThat(status(report, "exits-when-stdout-closes")).as(report.render()).isEqualTo(ConformanceReport.Status.PASS);
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aDescendantHoldingStdoutCannotOutliveTheTimeout() {
    long start = System.nanoTime();

    ConformanceReport report = run("orphan-holds-stdout", FULL, null, Duration.ofSeconds(3));

    assertThat(status(report, "harvest-completes")).as(report.render()).isEqualTo(FAIL);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
  }

  @ParameterizedTest
  @CsvSource({
    "check-emits-items, check-accepts-valid-config",
    "leaks-secret, secrets-stay-private",
    "sweep-first, sweep-is-complete",
    "crashes-on-end-cursor, resume-loses-nothing",
    "limited-no-items, limit-respected",
    "limited-no-checkpoint, limit-respected",
    "limited-stalls, limit-respected",
    "ignores-char-limit, limit-respected",
    "crash, harvest-completes",
    "garbage-stdout, harvest-completes",
    "accepts-anything, check-rejects-invalid-config",
    "no-items, harvest-emits-items",
    "undeclared-tombstone, capabilities-declared",
    "no-checkpoints, capabilities-declared",
    "unstable-ids, ids-stable-across-runs",
    "loses-on-resume, resume-loses-nothing",
    "sweeps-on-resume, sweep-is-complete",
    "ignores-limit, limit-respected",
    "sweeps-when-limited, limit-respected",
    "ignores-closed-stdout, exits-when-stdout-closes"
  })
  void faultyHarvesterFailsTheNamedCheck(String mode, String check) {
    ConformanceReport report = runMode(mode);

    assertThat(status(report, check)).as(report.render()).isEqualTo(FAIL);
    assertThat(report.passed()).isFalse();
  }

  @Test
  void aFailedHarvestReportsDependentChecksInsteadOfCrashing() {
    ConformanceReport report = runMode("crash");

    assertThat(status(report, "sweep-is-complete")).isEqualTo(FAIL);
    assertThat(report.render()).contains("harvest did not complete");
  }

  @Test
  void missingInvalidConfigSkipsThatCheck() {
    assertThat(status(run("ok", FULL, null, Duration.ofSeconds(30)), "check-rejects-invalid-config")).isEqualTo(SKIP);
  }

  @Test
  void withoutIncrementalResumeIsSkipped() {
    ConformanceReport report = run("ok", manifest(Capability.DELETES), INVALID_CONFIG, Duration.ofSeconds(30));

    assertThat(status(report, "resume-loses-nothing")).isEqualTo(SKIP);
  }

  @Test
  void aHungHarvesterTimesOutRatherThanHangingTheKit() {
    ConformanceReport report = run("hangs", FULL, null, Duration.ofSeconds(3));

    assertThat(status(report, "harvest-completes")).isEqualTo(FAIL);
  }

  private static int runMain(ByteArrayOutputStream out, String... args) {
    return ConformanceMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void mainReportsResultsThroughItsExitCode(@TempDir Path dir) throws Exception {
    List<String> command = new ArrayList<>(javaCommand("ok"));
    Path script = dir.resolve("run.sh");
    Files.writeString(script, "#!/bin/sh\nexec " + command.stream().map(a -> "'" + a + "'").reduce((a, b) -> a + " " + b).orElseThrow() + " \"$@\"\n");
    assertThat(script.toFile().setExecutable(true)).isTrue();
    Path manifest = Files.writeString(dir.resolve("harvester.toml"), """
      id = "fixture"
      name = "Fixture"
      version = "1"
      protocol = 1
      command = ["./run.sh"]
      capabilities = ["incremental", "deletes"]
      """);
    Path config = Files.writeString(dir.resolve("config.json"), CONFIG.toString());
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    int exit = runMain(out, "--manifest", manifest.toString(), "--config", config.toString());

    assertThat(exit).as(out.toString(StandardCharsets.UTF_8)).isZero();
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("harvest-completes");
  }

  @Test
  void mainWithoutRequiredArgumentsIsAUsageError() {
    assertThat(runMain(new ByteArrayOutputStream())).isEqualTo(2);
  }

  @Test
  void mainWithAMissingConfigFileIsAUsageError(@TempDir Path dir) {
    assertThat(runMain(new ByteArrayOutputStream(), "--manifest", dir.resolve("a").toString(), "--config", dir.resolve("b").toString()))
      .isEqualTo(2);
  }
}
