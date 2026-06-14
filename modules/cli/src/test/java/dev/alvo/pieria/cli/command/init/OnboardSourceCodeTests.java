package dev.alvo.pieria.cli.command.init;

import dev.alvo.pieria.api.request.CodeIndexRequest;
import dev.alvo.pieria.api.response.CodeIndexResponse;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@code pieria onboard --source-code}: the code step discovers tracked source files and
 * sends them to the daemon's code-index endpoint, and {@code --dry-run} lists without contacting the
 * daemon. The command talks to a {@link StubDaemon} over HTTP via {@code --daemon-url}, exercising the
 * real {@code HttpCodeIndexClient} / {@code HttpConfigClient}; {@code --config-dir} pins the global
 * config dir to the temp project.
 */
class OnboardSourceCodeTests {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static String responseJson(CodeIndexResponse response) {
    return MAPPER.writeValueAsString(response);
  }

  private static CodeIndexRequest parseCodeRequest(String body) {
    return MAPPER.readValue(body, CodeIndexRequest.class);
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
  void indexesDiscoveredSourceFiles(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("Main.java"), "class Main {}");

    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/code", 200, responseJson(new CodeIndexResponse(1, 0, 1, 0, 3, 1, 0, 1, 0, 1, 0)));

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isZero();
      CodeIndexRequest sent = parseCodeRequest(daemon.lastRequestTo("/code").body());
      assertThat(sent.files()).extracting(CodeIndexRequest.FileDto::repoRelPath).contains("Main.java");
      assertThat(r.out()).contains("Parsed 1 file");
    }
  }

  @Test
  void dryRunListsSourceFilesWithoutContactingDaemon(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("Main.java"), "class Main {}");

    try (StubDaemon daemon = StubDaemon.start()) {
      OnboardCommand cmd = command(proj, daemon.baseUrl());
      cmd.dryRun = true;

      Result r = run(cmd);

      assertThat(r.code()).isZero();
      assertThat(r.out()).contains("Would index").contains("Main.java");
      assertThat(daemon.requests()).isEmpty();
    }
  }

  @Test
  void projectConfigDrivesDiscoveryAndPushesOverrides(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("Main.java"), "class Main {}");
    Files.writeString(proj.resolve("query.sql"), "SELECT 1;");
    Files.createDirectories(proj.resolve(".pieria"));
    Files.writeString(proj.resolve(".pieria").resolve("config.toml"), """
      [discovery]
      source-extensions = ["sql"]

      [pieria.retrieval]
      weight-graph = 0.0
      """);

    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/code", 200, responseJson(new CodeIndexResponse(1, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0)));
      daemon.stub("/config", 200, "{}");

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isZero();
      // The project [discovery] override replaces the defaults: sql in, java out.
      CodeIndexRequest sent = parseCodeRequest(daemon.lastRequestTo("/code").body());
      assertThat(sent.files()).extracting(CodeIndexRequest.FileDto::repoRelPath).containsExactly("query.sql");
      // The [pieria] overrides were pushed to the profile.
      assertThat(daemon.lastRequestTo("/config").body()).contains("\"weight-graph\":0.0");
      assertThat(r.out()).contains("Pushed project config overrides");
    }
  }

  @Test
  void noOverridesMeansNoConfigPush(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("Main.java"), "class Main {}");

    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/code", 200, responseJson(new CodeIndexResponse(1, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0)));

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isZero();
      assertThat(daemon.lastRequestTo("/config")).isNull();
    }
  }

  private record Result(int code, String out) {
  }
}
