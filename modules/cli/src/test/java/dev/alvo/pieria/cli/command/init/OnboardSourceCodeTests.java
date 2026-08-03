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
 * Tests for code-only {@code pieria onboard --source-code}. Discovery happens daemon-side, so the
 * command sends only a {@code SourceSpec.SourceCode} (root + resolved {@code [discovery]} config) to
 * the onboarding endpoint; actual file enumeration is covered by the daemon's {@code CodeDiscovery}
 * tests.
 */
class OnboardSourceCodeTests {

  /** Terminal payload of a code-only composite task. */
  private static String codeTask(int files, int memories, int symbols, int edges, int summaries) {
    return "{\"status\":\"SUCCEEDED\",\"result\":{\"sources\":["
      + "{\"sourceType\":\"source-code\""
      + ",\"documents\":" + files + ",\"memoriesStored\":" + memories
      + ",\"symbols\":" + symbols + ",\"edges\":" + edges + ",\"summariesStored\":" + summaries + "}]}}";
  }

  /**
   * A path as it appears inside the JSON request body: Jackson escapes {@code \} as {@code \\}, so
   * on Windows a raw {@code Path.toString()} (backslash-separated) never appears literally in the
   * body. No-op on POSIX paths, which contain no backslashes.
   */
  private static String jsonPath(Path path) {
    return path.toString().replace("\\", "\\\\");
  }

  private static OnboardCommand command(Path proj, String daemonUrl) {
    OnboardCommand cmd = new OnboardCommand();
    cmd.projectDir = proj;
    cmd.daemonUrl = daemonUrl;
    cmd.configDir = proj;
    cmd.sourceCode = true;
    return cmd;
  }

  private static Result run(OnboardCommand cmd) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream orig = System.out;
    try {
      System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
      int code = cmd.call();
      return new Result(code, out.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(orig);
    }
  }

  @Test
  void sendsSourceCodeSpecAndReportsIndexCounts(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("Main.java"), "class Main {}");

    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stub("/tasks/t1", 200, codeTask(1, 1, 3, 1, 0));

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isZero();
      String codeBody = daemon.lastRequestTo("/onboard/async").body();
      assertThat(codeBody)
        .contains("\"type\":\"source-code\"")
        .contains(jsonPath(proj.toAbsolutePath().normalize()))
        .doesNotContain("\"type\":\"markdown\"")
        .doesNotContain("\"type\":\"text\"")
        .doesNotContain("\"type\":\"pdf\"");
      // Without --summarize the flag is absent so the daemon's config decides.
      assertThat(codeBody).doesNotContain("\"summarize\":true");
      assertThat(r.out()).contains("Indexed 1 file(s), 3 symbol(s), 1 edge(s)");
    }
  }

  @Test
  void summarizeFlagForcesSummariesAndReportsCounts(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("Main.java"), "class Main {}");

    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stub("/tasks/t1", 200, codeTask(1, 1, 3, 1, 2));

      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.summarize = true;
      Result r = run(cmd);

      assertThat(r.code()).isZero();
      assertThat(daemon.lastRequestTo("/onboard/async").body()).contains("\"summarize\":true");
      assertThat(r.out()).contains("2 summary memories written.");
    }
  }

  @Test
  void dryRunListsSourcesWithoutContactingDaemon(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("Main.java"), "class Main {}");

    try (StubDaemon daemon = StubDaemon.start()) {
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.dryRun = true;

      Result r = run(cmd);

      assertThat(r.code()).isZero();
      assertThat(r.out()).contains("Would seed").contains("1 source(s)").contains("source code under")
        .doesNotContain("markdown under").doesNotContain("text under").doesNotContain("PDFs under");
      assertThat(daemon.requests()).isEmpty();
    }
  }

  @Test
  void projectDiscoveryConfigTravelsInSpecAndOverridesArePushed(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("Main.java"), "class Main {}");
    Files.createDirectories(proj.resolve(".pieria"));
    Files.writeString(proj.resolve(".pieria").resolve("config.toml"), """
      [discovery]
      source-extensions = ["sql"]

      [pieria.retrieval]
      weight-graph = 0.0
      """);

    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stub("/tasks/t1", 200, codeTask(1, 1, 0, 0, 0));
      daemon.stub("/config", 200, "{}");

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isZero();
      // The project [discovery] override travels in the source-code spec for the daemon to apply.
      assertThat(daemon.lastRequestTo("/onboard/async").body()).contains("sql");
      // The [pieria] overrides were pushed to the profile.
      assertThat(daemon.lastRequestTo("/config").body()).contains("\"weight-graph\":0.0");
      assertThat(r.out()).contains("Pushed project config overrides");
      int configIndex = indexOfRequest(daemon, "/config");
      int onboardIndex = indexOfRequest(daemon, "/onboard/async");
      assertThat(configIndex).isLessThan(onboardIndex);
    }
  }

  @Test
  void noOverridesMeansNoConfigPush(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("Main.java"), "class Main {}");

    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stub("/tasks/t1", 200, codeTask(1, 1, 0, 0, 0));

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isZero();
      assertThat(daemon.lastRequestTo("/config")).isNull();
    }
  }

  private record Result(int code, String out) {
  }

  private static int indexOfRequest(StubDaemon daemon, String suffix) {
    for (int i = 0; i < daemon.requests().size(); i++) {
      if (daemon.requests().get(i).path().endsWith(suffix)) {
        return i;
      }
    }
    return Integer.MAX_VALUE;
  }
}
