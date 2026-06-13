package dev.alvo.pieria.cli.command.config;

import dev.alvo.pieria.cli.modules.config.ConfigClient;
import dev.alvo.pieria.cli.modules.config.ProjectConfigLoader;
import dev.alvo.pieria.cli.modules.init.IngestClient;
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
 * kebab-case JSON, clears when no override is set, and dry-run never contacts the daemon.
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

  private static ConfigSyncCommand command(Path proj, FakeConfigClient fake) {
    ConfigSyncCommand cmd = new ConfigSyncCommand();
    cmd.projectDir = proj;
    cmd.clientOverride = fake;
    cmd.loaderOverride = new ProjectConfigLoader(
      proj.resolve("global-config.toml"), proj.resolve(".pieria").resolve("config.toml"));
    return cmd;
  }

  private static void writeProjectConfig(Path proj, String content) throws IOException {
    Files.createDirectories(proj.resolve(".pieria"));
    Files.writeString(proj.resolve(".pieria").resolve("config.toml"), content);
  }

  @Test
  void pushesMergedOverridesAsKebabJson(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("global-config.toml"), """
      [pieria.retrieval]
      rrf-k = 30
      """);
    writeProjectConfig(proj, """
      [pieria.retrieval]
      weight-graph = 0.0
      """);
    FakeConfigClient fake = new FakeConfigClient();

    Result r = run(command(proj, fake));

    assertThat(r.code()).isZero();
    assertThat(fake.profile).isNotBlank();
    assertThat(fake.putBody).contains("\"rrf-k\":30").contains("\"weight-graph\":0.0");
    assertThat(r.out()).contains("Synced config overrides");
  }

  @Test
  void noOverridesPushesEmptyObjectToClear(@TempDir Path proj) {
    FakeConfigClient fake = new FakeConfigClient();

    Result r = run(command(proj, fake));

    assertThat(r.code()).isZero();
    assertThat(fake.putBody).isEqualTo("{}");
  }

  @Test
  void dryRunNeverContactsDaemon(@TempDir Path proj) throws IOException {
    writeProjectConfig(proj, """
      [pieria.ingestion]
      chunk-size-chars = 8000
      """);
    FakeConfigClient fake = new FakeConfigClient();
    ConfigSyncCommand cmd = command(proj, fake);
    cmd.dryRun = true;

    Result r = run(cmd);

    assertThat(r.code()).isZero();
    assertThat(r.out()).contains("Would push").contains("chunk-size-chars");
    assertThat(fake.pinged).isFalse();
    assertThat(fake.putBody).isNull();
  }

  @Test
  void daemonDownReturnsExit3(@TempDir Path proj) {
    FakeConfigClient fake = new FakeConfigClient();
    fake.reachability = IngestClient.Reachability.DAEMON_DOWN;

    Result r = run(command(proj, fake));

    assertThat(r.code()).isEqualTo(3);
    assertThat(r.err()).contains("not reachable");
  }

  @Test
  void malformedConfigReturnsExit2(@TempDir Path proj) throws IOException {
    writeProjectConfig(proj, "not [ valid toml =");
    FakeConfigClient fake = new FakeConfigClient();

    Result r = run(command(proj, fake));

    assertThat(r.code()).isEqualTo(2);
    assertThat(r.err()).contains("Failed to load config");
    assertThat(fake.putBody).isNull();
  }

  private record Result(int code, String out, String err) {
  }

  private static final class FakeConfigClient implements ConfigClient {
    boolean pinged;
    String profile;
    String putBody;
    IngestClient.Reachability reachability = IngestClient.Reachability.OK;

    @Override
    public IngestClient.Reachability ping() {
      pinged = true;
      return reachability;
    }

    @Override
    public ConfigResult put(String profile, String overridesJson) {
      this.profile = profile;
      this.putBody = overridesJson;
      return new Success("{\"retrieval\":{}}");
    }

    @Override
    public ConfigResult get(String profile) {
      return new Success("{}");
    }
  }
}
