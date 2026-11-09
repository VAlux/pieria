package dev.alvo.pieria.harvester.process;

import dev.alvo.pieria.harvester.conformance.FixtureHarvester;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.ProtocolViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HarvesterProcessTests {

  private static final String SECRET = "s3cr3t-for-process-tests";
  private static final Set<String> KEPT = Set.of("PATH", "HOME", "SystemRoot", "USERPROFILE", "TEMP", "TMP",
    "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy");

  private static HarvesterProcess start(String mode) throws Exception {
    List<String> command = List.of(ProcessHandle.current().info().command().orElseThrow(), "-cp",
      System.getProperty("java.class.path"), FixtureHarvester.class.getName(), mode);
    HarvestRequest request = new HarvestRequest(1, new ObjectMapper().createObjectNode().put("valid", true),
      Map.of("token", SECRET), null, HarvestRequest.Limits.NONE);
    return HarvesterProcess.start(command, "harvest", Path.of(".").toAbsolutePath(), request);
  }

  private static Instant in(Duration duration) {
    return Instant.now().plus(duration);
  }

  private static Instant soon() {
    return in(Duration.ofSeconds(30));
  }

  @Test
  void readsRecordsThenNullAtEndOfOutput() throws Exception {
    try (HarvesterProcess process = start("ok")) {
      List<HarvestRecord> records = new ArrayList<>();
      for (HarvestRecord record = process.next(soon()); record != null; record = process.next(soon())) {
        records.add(record);
      }

      assertThat(records).filteredOn(HarvestRecord.Document.class::isInstance).hasSize(3);
      assertThat(process.awaitExit(soon())).isTrue();
      assertThat(process.exitCode()).isZero();
      assertThat(process.next(soon())).as("stays at end of output").isNull();
    }
  }

  @Test
  void violationSurfacesFromNext() throws Exception {
    try (HarvesterProcess process = start("garbage-stdout")) {
      assertThatThrownBy(() -> process.next(soon())).isInstanceOf(ProtocolViolation.class);
    }
  }

  @Test
  void timesOutRatherThanBlocking() throws Exception {
    try (HarvesterProcess process = start("hangs")) {
      long begin = System.nanoTime();

      assertThatThrownBy(() -> process.next(in(Duration.ofMillis(500)))).isInstanceOf(TimeoutException.class);

      assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(3));
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void descendantHoldingStdoutCannotBlockTheDeadline() throws Exception {
    try (HarvesterProcess process = start("orphan-holds-stdout")) {
      assertThatThrownBy(() -> process.next(in(Duration.ofSeconds(2)))).isInstanceOf(TimeoutException.class);
    }
  }

  private static void drain(HarvesterProcess process) throws Exception {
    while (process.next(soon()) != null) {
      // Read to the end so the process can finish.
    }
    process.awaitExit(soon());
  }

  @Test
  void capturesStderrWithoutBlocking() throws Exception {
    try (HarvesterProcess process = start("leaks-secret")) {
      drain(process);

      assertThat(process.stderr()).contains(SECRET);
    }
  }

  @Test
  void doesNotReadAheadOfTheConsumer() throws Exception {
    try (HarvesterProcess process = start("slow-ok")) {
      assertThat(process.next(soon())).isNotNull();

      process.closeOutput();

      assertThat(process.awaitExit(in(Duration.ofSeconds(10)))).as("a closed pipe ends the harvester").isTrue();
    }
  }

  @Test
  void destroyEndsTheProcess() throws Exception {
    try (HarvesterProcess process = start("hangs")) {
      process.destroy();

      assertThat(process.awaitExit(in(Duration.ofSeconds(5)))).isTrue();
      assertThat(process.isAlive()).isFalse();
    }
  }

  @Test
  void exitCodeIsNegativeWhileRunning() throws Exception {
    try (HarvesterProcess process = start("hangs")) {
      assertThat(process.exitCode()).isEqualTo(-1);
    }
  }

  @Test
  void environmentIsScrubbed() throws Exception {
    List<String> leakable = System.getenv().keySet().stream()
      // macOS sets __CF_USER_TEXT_ENCODING in every new process, so it says nothing about what was passed on.
      .filter(name -> !KEPT.contains(name) && !name.startsWith("__CF_")).toList();
    assertThat(leakable).as("the test needs a variable that must not be passed on").isNotEmpty();

    try (HarvesterProcess process = start("prints-env")) {
      drain(process);

      assertThat(List.of(process.stderr().strip().split(","))).doesNotContainAnyElementsOf(leakable);
    }
  }
}
