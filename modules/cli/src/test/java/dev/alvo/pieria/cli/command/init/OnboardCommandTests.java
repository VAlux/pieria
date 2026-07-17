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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@code pieria onboard}. The command talks to a {@link StubDaemon} over HTTP via
 * {@code --daemon-url}, exercising the real {@code HttpOnboardClient}. Discovery/reading happen
 * daemon-side, so the command sends one ordered composite request and renders the per-source task
 * result; {@code --config-dir} pins the global config dir to the temp project so tests never read
 * the real OS config dir.
 *
 * <p>With no positional targets the command scans the project dir for everything (markdown, text,
 * pdf); positional targets switch it to per-target dispatch (URL / .md / .txt / .pdf / directory).
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

  /** Stub every onboard task as an immediate success so multi-source runs complete. */
  private static void stubSuccess(StubDaemon daemon) {
    daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
    daemon.stub("/tasks/t1", 200,
      "{\"status\":\"SUCCEEDED\",\"result\":{\"sources\":["
        + "{\"sourceType\":\"markdown\",\"documents\":1,\"memoriesStored\":1},"
        + "{\"sourceType\":\"text\",\"documents\":0,\"memoriesStored\":0},"
        + "{\"sourceType\":\"pdf\",\"documents\":0,\"memoriesStored\":0}]}}");
  }

  private static List<String> onboardBodies(StubDaemon daemon) {
    return daemon.requests().stream()
      .filter(r -> r.path().endsWith("/onboard/async"))
      .map(StubDaemon.Recorded::body)
      .toList();
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
  void dryRunReportsAllScanSourcesAndNeverContactsDaemon(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    try (StubDaemon daemon = StubDaemon.start()) {
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.dryRun = true;

      Result r = run(cmd);

      assertThat(r.code()).isZero();
      assertThat(r.out())
        .contains("Would seed")
        .contains("markdown under")
        .contains("text under")
        .contains("PDFs under");
      assertThat(daemon.requests()).isEmpty();
    }
  }

  @Test
  void scanModeSendsMarkdownTextAndPdfRootedAtProjectDir(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    String root = proj.toAbsolutePath().normalize().toString();
    try (StubDaemon daemon = StubDaemon.start()) {
      stubSuccess(daemon);

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isZero();
      List<String> bodies = onboardBodies(daemon);
      assertThat(bodies).hasSize(1);
      assertThat(bodies.getFirst()).contains("\"type\":\"markdown\"").contains(root)
        .contains("\"type\":\"text\"").contains("\"type\":\"pdf\"");
    }
  }

  @Test
  void sourceCodeFlagAddsSourceCodeSpecInScanMode(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    try (StubDaemon daemon = StubDaemon.start()) {
      stubSuccess(daemon);
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.sourceCode = true;

      Result r = run(cmd);

      assertThat(r.code()).isZero();
      assertThat(onboardBodies(daemon))
        .hasSize(1)
        .anySatisfy(b -> assertThat(b).contains("\"type\":\"source-code\""));
    }
  }

  @Test
  void refreshFlagLandsInEveryContentSpec(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    try (StubDaemon daemon = StubDaemon.start()) {
      stubSuccess(daemon);
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.refresh = true;

      Result r = run(cmd);

      assertThat(r.code()).isZero();
      List<String> bodies = onboardBodies(daemon);
      assertThat(bodies).hasSize(1);
      assertThat(bodies.getFirst()).contains("\"refresh\":true");
      assertThat(count(bodies.getFirst(), "\"refresh\":true")).isEqualTo(3);
    }
  }

  @Test
  void refreshIsOmittedFromSpecsByDefault(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    try (StubDaemon daemon = StubDaemon.start()) {
      stubSuccess(daemon);

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isZero();
      assertThat(onboardBodies(daemon)).allSatisfy(b -> assertThat(b).doesNotContain("refresh"));
    }
  }

  @Test
  void urlTargetsCoalesceIntoOneWebSpec(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    try (StubDaemon daemon = StubDaemon.start()) {
      stubSuccess(daemon);
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.targets = List.of("http://example.com", "https://example.org");

      Result r = run(cmd);

      assertThat(r.code()).isZero();
      List<String> bodies = onboardBodies(daemon);
      assertThat(bodies).hasSize(1);
      assertThat(bodies.get(0))
        .contains("\"type\":\"web\"")
        .contains("http://example.com")
        .contains("https://example.org");
    }
  }

  @Test
  void fileTargetsDispatchByExtensionToAbsoluteSpecs(@TempDir Path proj) throws IOException {
    Path md = proj.resolve("guide.md");
    Path txt = proj.resolve("notes.txt");
    Path pdf = proj.resolve("paper.pdf");
    Files.writeString(md, "# Guide");
    Files.writeString(txt, "plain notes");
    Files.writeString(pdf, "%PDF-1.4");
    try (StubDaemon daemon = StubDaemon.start()) {
      stubSuccess(daemon);
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.targets = List.of(md.toString(), txt.toString(), pdf.toString());

      Result r = run(cmd);

      assertThat(r.code()).isZero();
      List<String> bodies = onboardBodies(daemon);
      assertThat(bodies).hasSize(1);
      assertThat(bodies.getFirst()).contains("\"type\":\"markdown\"")
        .contains(md.toAbsolutePath().normalize().toString())
        .contains("\"type\":\"text\"").contains(txt.toAbsolutePath().normalize().toString())
        .contains("\"type\":\"pdf\"").contains(pdf.toAbsolutePath().normalize().toString());
    }
  }

  @Test
  void directoryTargetExpandsToMarkdownTextAndPdf(@TempDir Path proj) throws IOException {
    Path docs = Files.createDirectory(proj.resolve("docs"));
    Files.writeString(docs.resolve("a.md"), "# A");
    String root = docs.toAbsolutePath().normalize().toString();
    try (StubDaemon daemon = StubDaemon.start()) {
      stubSuccess(daemon);
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.targets = List.of(docs.toString());

      Result r = run(cmd);

      assertThat(r.code()).isZero();
      List<String> bodies = onboardBodies(daemon);
      assertThat(bodies).hasSize(1);
      assertThat(bodies.getFirst()).contains(root).contains("\"type\":\"markdown\"")
        .contains("\"type\":\"text\"").contains("\"type\":\"pdf\"");
    }
  }

  @Test
  void unsupportedTargetIsWarnedAndSkippedWhileOthersStillSend(@TempDir Path proj) throws IOException {
    Path md = proj.resolve("guide.md");
    Files.writeString(md, "# Guide");
    try (StubDaemon daemon = StubDaemon.start()) {
      stubSuccess(daemon);
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.targets = List.of(proj.resolve("data.csv").toString(), md.toString());

      Result r = run(cmd);

      assertThat(r.code()).isZero();
      assertThat(r.err()).contains("Unsupported target").contains("data.csv");
      List<String> bodies = onboardBodies(daemon);
      assertThat(bodies).hasSize(1);
      assertThat(bodies.get(0)).contains("\"type\":\"markdown\"");
    }
  }

  @Test
  void allUnsupportedTargetsExitTwoWithoutContactingDaemon(@TempDir Path proj) throws IOException {
    try (StubDaemon daemon = StubDaemon.start()) {
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.targets = List.of(proj.resolve("data.csv").toString());

      Result r = run(cmd);

      assertThat(r.code()).isEqualTo(2);
      assertThat(r.err()).contains("No onboardable targets");
      assertThat(onboardBodies(daemon)).isEmpty();
    }
  }

  @Test
  void relativeFilePathResolvesToAbsoluteRoot(@TempDir Path proj) throws IOException {
    // A relative target resolves against the process CWD; a nonexistent one still sends (daemon 400s),
    // so we can assert the absolute root without depending on the CWD layout.
    try (StubDaemon daemon = StubDaemon.start()) {
      stubSuccess(daemon);
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.targets = List.of("notes.txt");
      cmd.dryRun = true;

      Result r = run(cmd);

      assertThat(r.code()).isZero();
      String cwd = Path.of("").toAbsolutePath().resolve("notes.txt").normalize().toString();
      assertThat(r.out()).contains("text under " + cwd);
    }
  }

  @Test
  void successReportsStoredCount(@TempDir Path proj) throws IOException {
    Path md = proj.resolve("guide.md");
    Files.writeString(md, "# Guide");
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stub("/tasks/t1", 200,
        "{\"status\":\"SUCCEEDED\",\"result\":{\"sources\":[{\"sourceType\":\"markdown\","
          + "\"documents\":2,\"memoriesStored\":5}],\"graphEnrichmentTaskId\":\"g1\",\"graphCandidates\":5}}");

      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.targets = List.of(md.toString());
      Result r = run(cmd);

      assertThat(r.code()).isZero();
      assertThat(r.out()).contains("Stored 5 memories");
    }
  }

  private static int count(String value, String needle) {
    return (value.length() - value.replace(needle, "").length()) / needle.length();
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

  @Test
  void noEnrichGraphSendsFalseAndDoesNotWaitForAChildTask(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    try (StubDaemon daemon = StubDaemon.start()) {
      stubSuccess(daemon);
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.noEnrichGraph = true;

      Result result = run(cmd);

      assertThat(result.code()).isZero();
      assertThat(onboardBodies(daemon).getFirst()).contains("\"enrichGraph\":false");
      assertThat(result.out()).contains("Graph enrichment was skipped");
    }
  }

  @Test
  void waitForEnrichmentPollsTheChildTask(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stub("/tasks/t1", 200,
        "{\"status\":\"SUCCEEDED\",\"result\":{\"sources\":[],"
          + "\"graphEnrichmentTaskId\":\"g1\",\"graphCandidates\":2}}");
      daemon.stub("/tasks/g1", 200,
        "{\"status\":\"SUCCEEDED\",\"result\":{\"memoriesScanned\":2}}");
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.waitForEnrichment = true;

      Result result = run(cmd);

      assertThat(result.code()).isZero();
      assertThat(result.out()).contains("Waiting for graph enrichment").contains("Graph enrichment complete");
      assertThat(daemon.lastRequestTo("/tasks/g1")).isNotNull();
    }
  }

  @Test
  void enrichmentFlagsAreMutuallyExclusive(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    OnboardCommand cmd = command(proj, StubDaemon.unreachableUrl());
    cmd.noEnrichGraph = true;
    cmd.waitForEnrichment = true;

    Result result = run(cmd);

    assertThat(result.code()).isEqualTo(2);
    assertThat(result.err()).contains("mutually exclusive");
  }

  private record Result(int code, String out, String err) {
  }
}
