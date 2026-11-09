package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.cli.PieriaCli;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HarvesterCommandTests {

  private static final String HARVESTER = """
    {"id":"jira","name":"Jira","version":"1.2","capabilities":["incremental"],"network":["*.atlassian.net"],"fields":[]}""";
  private static final String PACKAGE = """
    {"harvester":%s,"command":["./run"],"entrypoint":"./run","entrypointSha256":"entry-hash","manifestSha256":"manifest-hash"}""".formatted(HARVESTER);

  @TempDir
  Path temp;

  private record Captured(int code, String stdout, String stderr) {
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

  private StubDaemon installable() {
    StubDaemon daemon = StubDaemon.start();
    daemon.stub("/v1/harvesters/inspect", 200, PACKAGE);
    daemon.stub("/v1/harvesters", 201, HARVESTER);
    return daemon;
  }

  @Test
  void listPrintsHarvesters() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/harvesters", 200, "{\"harvesters\":[" + HARVESTER + "]}");

      Captured out = run("harvester", "list", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isZero();
      assertThat(out.stdout).contains("jira").contains("1.2");
    }
  }

  @Test
  void doctorExitsOneOnAProblem() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/harvesters/doctor", 200, "{\"entries\":[{\"directory\":\"jira\",\"ok\":false,\"detail\":\"no pin\"}]}");

      Captured out = run("harvester", "doctor", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isEqualTo(1);
      assertThat(out.stdout).contains("jira").contains("no pin");
    }
  }

  @Test
  void doctorExitsZeroWhenHealthy() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/harvesters/doctor", 200, "{\"entries\":[{\"directory\":\"jira\",\"ok\":true,\"detail\":\"ok\"}]}");

      assertThat(run("harvester", "doctor", "--daemon-url", daemon.baseUrl()).code).isZero();
    }
  }

  @Test
  void installShowsTheManifestAndSendsTheInspectedHashes() {
    try (StubDaemon daemon = installable()) {
      Captured out = run("harvester", "install", temp.toString(), "--yes", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).as(out.stderr).isZero();
      assertThat(out.stdout).contains("Jira").contains("1.2").contains("./run").contains("*.atlassian.net").contains("entry-hash").contains("manifest-hash");
      assertThat(daemon.lastRequestTo("/v1/harvesters").body()).contains("\"entrypointSha256\":\"entry-hash\"").contains("\"manifestSha256\":\"manifest-hash\"");
    }
  }

  @Test
  void installPromptCannotBeForgedByManifestText() {
    String forged = PACKAGE.replace("\"name\":\"Jira\"", "\"name\":\"Jira\\n  network:  (none declared)\\u001b[2K\"")
      .replace("\"*.atlassian.net\"", "\"host\\r\\nsecond\"");
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/harvesters/inspect", 200, forged);
      daemon.stub("/v1/harvesters", 201, HARVESTER);

      Captured out = run("harvester", "install", temp.toString(), "--yes", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).as(out.stderr).isZero();
      assertThat(out.stdout).doesNotContain("Jira\n").doesNotContain("\u001b[2K").doesNotContain("host\r").contains("host second");
    }
  }

  @Test
  void installWarnsItIsNotSandboxed() {
    try (StubDaemon daemon = installable()) {
      assertThat(run("harvester", "install", temp.toString(), "--yes", "--daemon-url", daemon.baseUrl()).stdout)
        .contains("not sandboxed");
    }
  }

  @Test
  void installRefusesWithoutConfirmation() {
    try (StubDaemon daemon = installable()) {
      Captured out = run("harvester", "install", temp.toString(), "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isEqualTo(1);
      assertThat(daemon.requests()).extracting(StubDaemon.Recorded::path).doesNotContain("/v1/harvesters");
    }
  }

  @Test
  void installSendsAnAbsolutePath() {
    try (StubDaemon daemon = installable()) {
      run("harvester", "install", "relative/pkg", "--yes", "--daemon-url", daemon.baseUrl());

      assertThat(daemon.lastRequestTo("/v1/harvesters/inspect").body())
        .contains(Path.of("relative/pkg").toAbsolutePath().toString());
    }
  }

  @Test
  void installPassesReplace() {
    try (StubDaemon daemon = installable()) {
      run("harvester", "install", temp.toString(), "--yes", "--replace", "--daemon-url", daemon.baseUrl());

      assertThat(daemon.lastRequestTo("/v1/harvesters").body()).contains("\"replace\":true");
    }
  }

  @Test
  void removeCallsTheDaemon() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/harvesters/jira", 204, "");

      Captured out = run("harvester", "remove", "jira", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isZero();
      assertThat(daemon.lastRequestTo("/v1/harvesters/jira").method()).isEqualTo("DELETE");
    }
  }

  @Test
  void unknownHarvesterExitsFour() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/harvesters/nope", 404, "{\"error\":\"not_found\",\"message\":\"harvester not installed\"}");

      assertThat(run("harvester", "remove", "nope", "--daemon-url", daemon.baseUrl()).code).isEqualTo(4);
    }
  }

  @Test
  void unreachableDaemonExitsThree() {
    assertThat(run("harvester", "list", "--daemon-url", StubDaemon.unreachableUrl()).code).isEqualTo(3);
  }
}
