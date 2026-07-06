package dev.alvo.pieria.cli.command;

import dev.alvo.pieria.cli.PieriaCli;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@code pieria profile create} and {@code pieria profile delete} against the
 * {@link StubDaemon}, driving the real {@code ProfileApiClient} HTTP paths through {@code --daemon-url}.
 */
class ProfileCreateDeleteCommandTests {

  @Test
  void createSendsPutAndReportsSuccess() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/profiles/newp", 201,
        "{\"name\":\"newp\",\"createdAt\":\"2026-01-01T00:00:00Z\",\"memoryCount\":0}");

      Captured out = run("profile", "create", "newp", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isEqualTo(0);
      assertThat(out.stdout).contains("Created profile").contains("newp");
      StubDaemon.Recorded req = daemon.lastRequestTo("/v1/profiles/newp");
      assertThat(req.method()).isEqualTo("PUT");
    }
  }

  @Test
  void createOnExistingProfileReportsConflict() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/profiles/dupe", 409,
        "{\"error\":\"conflict\",\"message\":\"A profile named 'dupe' already exists\"}");

      Captured out = run("profile", "create", "dupe", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isEqualTo(1);
      assertThat(out.stderr).contains("already exists");
    }
  }

  @Test
  void deleteSendsDeleteAndReportsSuccess() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/profiles/gone", 204, "");

      Captured out = run("profile", "delete", "gone", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isEqualTo(0);
      assertThat(out.stdout).contains("Deleted profile").contains("gone");
      StubDaemon.Recorded req = daemon.lastRequestTo("/v1/profiles/gone");
      assertThat(req.method()).isEqualTo("DELETE");
    }
  }

  @Test
  void deleteMissingProfileExitsWithCode4() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/profiles/nope", 404,
        "{\"error\":\"not_found\",\"message\":\"No profile named 'nope'\"}");

      Captured out = run("profile", "delete", "nope", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isEqualTo(4);
      assertThat(out.stderr).contains("No profile named");
    }
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
