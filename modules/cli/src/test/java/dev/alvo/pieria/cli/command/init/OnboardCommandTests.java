package dev.alvo.pieria.cli.command.init;

import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@code pieria onboard}: the markdown seeding path. The command talks to a
 * {@link StubDaemon} over HTTP via {@code --daemon-url}, exercising the real {@code HttpOnboardClient}.
 * Discovery/reading now happen daemon-side, so the command only sends a {@code SourceSpec} and
 * renders the task result; {@code --config-dir} pins the global config dir to the temp project so
 * tests never read the real OS config dir.
 */
class OnboardCommandTests {

  private static void writeReadme(Path proj) throws IOException {
    Files.writeString(proj.resolve("README.md"), "# Project\nSome durable knowledge.");
  }

  private static OnboardCommand command(Path proj, String daemonUrl) {
    OnboardCommand cmd = new OnboardCommand();
    cmd.projectDir = proj;
    cmd.daemonUrl = daemonUrl;
    cmd.configDir = proj;
    return cmd;
  }

  private static Result run(OnboardCommand cmd) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream origOut = System.out;
    PrintStream origErr = System.err;
    try {
      System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
      System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
      int code = cmd.call();
      return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(origOut);
      System.setErr(origErr);
    }
  }

  @Test
  void dryRunReportsSourcesAndNeverContactsDaemon(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    try (StubDaemon daemon = StubDaemon.start()) {
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.dryRun = true;

      Result r = run(cmd);

      assertThat(r.code()).isZero();
      assertThat(r.out()).contains("Would seed").contains("markdown under");
      assertThat(daemon.requests()).isEmpty();
    }
  }

  @Test
  void sendsMarkdownSourceSpecWithProjectRoot(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stub("/tasks/t1", 200,
        "{\"status\":\"SUCCEEDED\",\"result\":{\"sourceType\":\"markdown\",\"documents\":1,\"memoriesStored\":3}}");

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isZero();
      StubDaemon.Recorded posted = daemon.lastRequestTo("/onboard/async");
      assertThat(posted.body())
        .contains("\"type\":\"markdown\"")
        .contains(proj.toAbsolutePath().normalize().toString());
    }
  }

  @Test
  void pdfFlagSendsPdfSourceSpecWithProjectRoot(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stub("/tasks/t1", 200,
        "{\"status\":\"SUCCEEDED\",\"result\":{\"sourceType\":\"pdf\",\"documents\":1,\"memoriesStored\":2}}");

      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.pdf = true;
      Result r = run(cmd);

      assertThat(r.code()).isZero();
      StubDaemon.Recorded posted = daemon.lastRequestTo("/onboard/async");
      assertThat(posted.body())
        .contains("\"type\":\"pdf\"")
        .contains(proj.toAbsolutePath().normalize().toString());
    }
  }

  @Test
  void successReportsStoredCount(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stub("/tasks/t1", 200,
        "{\"status\":\"SUCCEEDED\",\"result\":{\"sourceType\":\"markdown\",\"documents\":2,\"memoriesStored\":5}}");

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isZero();
      assertThat(r.out()).contains("Stored 5 memories");
    }
  }

  @Test
  void daemonDownOnPingReturnsExit3(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    Result r = run(command(proj, StubDaemon.unreachableUrl()));

    assertThat(r.code()).isEqualTo(3);
    assertThat(r.err()).contains("not reachable");
  }

  @Test
  void modelUnavailableReturnsExit4(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stub("/tasks/t1", 200, "{\"status\":\"FAILED\",\"errorKind\":\"model-unavailable\"}");

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isEqualTo(4);
      assertThat(r.err()).contains("model provider");
    }
  }

  @Test
  void modelUnavailableSurfacesTheDaemonReason(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stub("/tasks/t1", 200,
        "{\"status\":\"FAILED\",\"errorKind\":\"model-unavailable\","
          + "\"errorMessage\":\"HTTP 404: model or deployment not found\"}");

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isEqualTo(4);
      assertThat(r.err()).contains("HTTP 404: model or deployment not found");
    }
  }

  private record Result(int code, String out, String err) {
  }
}
