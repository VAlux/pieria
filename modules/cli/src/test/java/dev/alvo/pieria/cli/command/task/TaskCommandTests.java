package dev.alvo.pieria.cli.command.task;

import dev.alvo.pieria.cli.PieriaCli;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@code pieria task} (list / attach / kill) end-to-end against a {@link StubDaemon},
 * driving the real {@link dev.alvo.pieria.cli.modules.task.HttpTaskClient} through {@code --daemon-url}.
 * Network-free (loopback only).
 */
class TaskCommandTests {

  @Test
  void listPrintsRunningAndFinishedTasks() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/tasks", 200, """
        {"tasks":[\
        {"id":"3f2ab100","kind":"onboard","profile":"pieria","status":"RUNNING","phase":"verify","done":12,"total":40},\
        {"id":"9c7d0400","kind":"code","profile":"pieria","status":"SUCCEEDED","phase":"index","done":88,"total":88}]}""");

      Captured out = run("task", "list", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isEqualTo(0);
      assertThat(out.stdout)
        .contains("3f2ab100").contains("onboard").contains("pieria")
        .contains("RUNNING").contains("verify 12/40 (30%)")
        .contains("9c7d0400").contains("SUCCEEDED");
    }
  }

  @Test
  void listWithNoTasksPrintsFriendlyMessage() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/tasks", 200, "{\"tasks\":[]}");

      Captured out = run("task", "list", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isEqualTo(0);
      assertThat(out.stdout).contains("No tasks");
    }
  }

  @Test
  void attachByIdFollowsProgressToSuccess() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stubSequence("/v1/tasks/t1",
        "{\"status\":\"RUNNING\",\"phase\":\"extract\",\"done\":1,\"total\":2}",
        "{\"status\":\"SUCCEEDED\",\"result\":{\"count\":4}}");

      Captured out = run("task", "t1", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isEqualTo(0);
      assertThat(out.stdout).contains("succeeded").contains("Stored 4");
    }
  }

  @Test
  void killCancelsTask() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/tasks/t1", 200, "{\"status\":\"CANCELLED\"}");

      Captured out = run("task", "kill", "t1", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isEqualTo(0);
      assertThat(out.stdout).contains("cancelled");
    }
  }

  @Test
  void killUnknownTaskExitsNotFound() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/tasks/zzz", 404, "{\"message\":\"No task with id 'zzz'\"}");

      Captured out = run("task", "kill", "zzz", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isEqualTo(4);
      assertThat(out.stderr).contains("No such task");
    }
  }

  @Test
  void listExitsWithCode3WhenDaemonUnreachable() {
    Captured out = run("task", "list", "--daemon-url", StubDaemon.unreachableUrl());

    assertThat(out.code).isEqualTo(3);
    assertThat(out.stderr).contains("not reachable");
  }

  private Captured run(String... args) {
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code;
    try {
      System.setOut(new PrintStream(out));
      System.setErr(new PrintStream(err));
      code = new CommandLine(new PieriaCli()).execute(args);
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
    return new Captured(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private record Captured(int code, String stdout, String stderr) {
  }
}
