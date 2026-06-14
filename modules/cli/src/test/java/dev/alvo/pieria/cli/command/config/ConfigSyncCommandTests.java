package dev.alvo.pieria.cli.command.config;

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
 * Tests for {@code pieria config sync}: pushes the merged (project &gt; global) overrides as
 * kebab-case JSON, clears when no override is set, and dry-run never contacts the daemon. The
 * command talks to a {@link StubDaemon} over HTTP via {@code --daemon-url}, exercising the real
 * {@code HttpConfigClient}; the global config dir is pinned to the temp project via
 * {@code --config-dir} so tests never read the real OS config dir.
 */
class ConfigSyncCommandTests {

  private static Result run(ConfigSyncCommand cmd) {
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

  private static ConfigSyncCommand command(Path proj, String daemonUrl) {
    ConfigSyncCommand cmd = new ConfigSyncCommand();
    cmd.projectDir = proj;
    cmd.daemonUrl = daemonUrl;
    cmd.configDir = proj; // global config.toml lives at <proj>/config.toml
    return cmd;
  }

  private static void writeGlobalConfig(Path proj, String content) throws IOException {
    Files.writeString(proj.resolve("config.toml"), content);
  }

  private static void writeProjectConfig(Path proj, String content) throws IOException {
    Files.createDirectories(proj.resolve(".pieria"));
    Files.writeString(proj.resolve(".pieria").resolve("config.toml"), content);
  }

  @Test
  void pushesMergedOverridesAsKebabJson(@TempDir Path proj) throws IOException {
    writeGlobalConfig(proj, """
      [pieria.retrieval]
      rrf-k = 30
      """);
    writeProjectConfig(proj, """
      [pieria.retrieval]
      weight-graph = 0.0
      """);

    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/config", 200, "{\"retrieval\":{}}");

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isZero();
      StubDaemon.Recorded put = daemon.lastRequestTo("/config");
      assertThat(put.method()).isEqualTo("PUT");
      assertThat(put.body()).contains("\"rrf-k\":30").contains("\"weight-graph\":0.0");
      assertThat(r.out()).contains("Synced config overrides");
    }
  }

  @Test
  void noOverridesPushesEmptyObjectToClear(@TempDir Path proj) {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/config", 200, "{}");

      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isZero();
      assertThat(daemon.lastRequestTo("/config").body()).isEqualTo("{}");
    }
  }

  @Test
  void dryRunNeverContactsDaemon(@TempDir Path proj) throws IOException {
    writeProjectConfig(proj, """
      [pieria.ingestion]
      chunk-size-chars = 8000
      """);

    try (StubDaemon daemon = StubDaemon.start()) {
      ConfigSyncCommand cmd = command(proj, daemon.baseUrl());
      cmd.dryRun = true;

      Result r = run(cmd);

      assertThat(r.code()).isZero();
      assertThat(r.out()).contains("Would push").contains("chunk-size-chars");
      assertThat(daemon.requests()).isEmpty();
    }
  }

  @Test
  void daemonDownReturnsExit3(@TempDir Path proj) {
    Result r = run(command(proj, StubDaemon.unreachableUrl()));

    assertThat(r.code()).isEqualTo(3);
    assertThat(r.err()).contains("not reachable");
  }

  @Test
  void malformedConfigReturnsExit2(@TempDir Path proj) throws IOException {
    writeProjectConfig(proj, "not [ valid toml =");

    try (StubDaemon daemon = StubDaemon.start()) {
      Result r = run(command(proj, daemon.baseUrl()));

      assertThat(r.code()).isEqualTo(2);
      assertThat(r.err()).contains("Failed to load config");
      assertThat(daemon.requests()).isEmpty();
    }
  }

  private record Result(int code, String out, String err) {
  }
}
