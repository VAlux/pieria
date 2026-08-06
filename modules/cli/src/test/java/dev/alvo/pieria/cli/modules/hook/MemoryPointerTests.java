package dev.alvo.pieria.cli.modules.hook;

import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryPointerTests {

  private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

  private HookContext context(String daemonUrl, Path dir) {
    return new HookContext(
      Map.of("PIERIA_DAEMON_URL", daemonUrl, "PIERIA_PROFILE", "proj")::get, dir, "claude-code");
  }

  /**
   * {@code lastMemoryAt} is expressed as an offset from the current clock, never a literal date:
   * {@code render} compares against {@link Instant#now()}, so a fixed timestamp silently changes
   * age every day and the assertion rots overnight.
   */
  private String stats(long totalActive, Duration age) {
    return "{\"name\":\"proj\",\"totalActive\":" + totalActive
      + ",\"superseded\":0,\"sessions\":1,\"lastMemoryAt\":"
      + (age == null ? "null" : "\"" + Instant.now().minus(age) + "\"") + "}";
  }

  @Test
  void pointsAtTheStoreWithCountAndFreshness(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/stats", 200, stats(342, Duration.ofDays(2)));

      HookOutcome outcome = MemoryPointer.render(context(daemon.baseUrl(), tmp));

      assertThat(outcome).isInstanceOf(HookOutcome.Ok.class);
      assertThat(((HookOutcome.Ok) outcome).stdout())
        .startsWith("[pieria] 342 memories for profile \"proj\" (latest 2d ago).")
        .contains("`recall`")
        .contains("`remember`");
    }
  }

  @Test
  void injectsNothingWhenTheProfileIsEmpty(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/stats", 200, stats(0, null));

      HookOutcome outcome = MemoryPointer.render(context(daemon.baseUrl(), tmp));

      assertThat(outcome).isInstanceOf(HookOutcome.Skipped.class);
    }
  }

  @Test
  void skipsWithoutCallingStatsWhenDaemonIsDown(@TempDir Path tmp) {
    HookOutcome outcome = MemoryPointer.render(context(StubDaemon.unreachableUrl(), tmp));

    assertThat(outcome).isInstanceOf(HookOutcome.Skipped.class);
  }

  @Test
  void staysQuietForAProfileTheDaemonHasNeverSeen(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/stats", 404, "{\"error\":\"not_found\",\"message\":\"No profile named 'proj'\"}");

      HookOutcome outcome = MemoryPointer.render(context(daemon.baseUrl(), tmp));

      assertThat(outcome).isInstanceOf(HookOutcome.Skipped.class);
    }
  }

  @Test
  void reportsFailureWhenStatsErrors(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/stats", 500, "{\"message\":\"boom\"}");

      HookOutcome outcome = MemoryPointer.render(context(daemon.baseUrl(), tmp));

      assertThat(outcome).isInstanceOf(HookOutcome.Failed.class);
    }
  }

  @Test
  void usesTheSingularForASingleMemory() {
    assertThat(MemoryPointer.text("proj", 1, NOW.minusSeconds(30), NOW))
      .startsWith("[pieria] 1 memory for profile \"proj\" (latest just now).");
  }

  @Test
  void omitsFreshnessWhenNoMemoryTimestampIsKnown() {
    assertThat(MemoryPointer.text("proj", 7, null, NOW))
      .startsWith("[pieria] 7 memories for profile \"proj\". Call the pieria `recall` tool");
  }

  @Test
  void tellsTheAgentWhatMemoryHoldsThatTheRepoDoesNot() {
    assertThat(MemoryPointer.text("proj", 7, null, NOW))
      .contains("prior decisions")
      .contains("rejected approaches")
      .contains("aren't in the repo's files");
  }
}
