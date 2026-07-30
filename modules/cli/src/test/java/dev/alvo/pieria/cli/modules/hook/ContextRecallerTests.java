package dev.alvo.pieria.cli.modules.hook;

import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextRecallerTests {

  private HookContext context(String daemonUrl, Path dir) {
    return new HookContext(
      Map.of("PIERIA_DAEMON_URL", daemonUrl, "PIERIA_PROFILE", "proj")::get, dir, "claude-code");
  }

  @Test
  void returnsInjectionBlockOnStdout(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/recall", 200, "[pieria] prior context\n- (fact) x\n");

      HookOutcome outcome = ContextRecaller.recall(context(daemon.baseUrl(), tmp), "why", 10);

      assertThat(outcome).isInstanceOf(HookOutcome.Ok.class);
      assertThat(((HookOutcome.Ok) outcome).stdout()).isEqualTo("[pieria] prior context\n- (fact) x\n");
    }
  }

  @Test
  void producesNothingWhenDaemonRecallsNothing(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/recall", 204, "");

      HookOutcome outcome = ContextRecaller.recall(context(daemon.baseUrl(), tmp), "why", 10);

      assertThat(outcome).isInstanceOf(HookOutcome.Ok.class);
      assertThat(((HookOutcome.Ok) outcome).stdout()).isEmpty();
    }
  }

  @Test
  void sendsQueryAndLimitInTheRequestBody(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/recall", 204, "");

      ContextRecaller.recall(context(daemon.baseUrl(), tmp), "what changed", 3);

      StubDaemon.Recorded request = daemon.lastRequestTo("/recall");
      assertThat(request.body()).contains("\"query\":\"what changed\"").contains("\"limit\":3");
    }
  }

  @Test
  void skipsWithoutRecallingWhenDaemonIsDown(@TempDir Path tmp) {
    HookOutcome outcome = ContextRecaller.recall(context(StubDaemon.unreachableUrl(), tmp), "why", 10);

    assertThat(outcome).isInstanceOf(HookOutcome.Skipped.class);
  }

  @Test
  void reportsFailureWhenRecallItselfErrors(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/recall", 500, "{\"message\":\"boom\"}");

      HookOutcome outcome = ContextRecaller.recall(context(daemon.baseUrl(), tmp), "why", 10);

      assertThat(outcome).isInstanceOf(HookOutcome.Failed.class);
    }
  }
}
