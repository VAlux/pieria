package dev.alvo.pieria.cli.command.init;

import dev.alvo.pieria.api.request.CodeIndexRequest;
import dev.alvo.pieria.api.response.CodeIndexResponse;
import dev.alvo.pieria.cli.modules.init.CodeIndexClient;
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
 * Tests for {@code pieria onboard --source-code}: the code step discovers tracked source files and
 * sends them through the {@link CodeIndexClient}, and {@code --dry-run} lists without contacting the
 * daemon.
 */
class OnboardSourceCodeTests {

  /** Confine config loading to the temp project so tests never read the real OS config dir. */
  private static dev.alvo.pieria.cli.modules.config.ProjectConfigLoader hermeticLoader(Path proj) {
    return new dev.alvo.pieria.cli.modules.config.ProjectConfigLoader(
      proj.resolve("global-config.toml"), proj.resolve(".pieria").resolve("config.toml"));
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

    OnboardCommand cmd = new OnboardCommand();
    cmd.projectDir = proj;
    cmd.loaderOverride = hermeticLoader(proj);
    cmd.sourceCode = true;
    FakeCodeClient fake = new FakeCodeClient();
    fake.result = new CodeIndexClient.Success(new CodeIndexResponse(1, 0, 1, 0, 3, 1, 0, 1, 0, 1, 0));
    cmd.codeClientOverride = fake;

    Result r = run(cmd);

    assertThat(r.code()).isZero();
    assertThat(fake.indexed).isTrue();
    assertThat(fake.body.files()).extracting(CodeIndexRequest.FileDto::repoRelPath).contains("Main.java");
    assertThat(r.out()).contains("Parsed 1 file");
  }

  @Test
  void dryRunListsSourceFilesWithoutContactingDaemon(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("Main.java"), "class Main {}");

    OnboardCommand cmd = new OnboardCommand();
    cmd.projectDir = proj;
    cmd.loaderOverride = hermeticLoader(proj);
    cmd.sourceCode = true;
    cmd.dryRun = true;
    FakeCodeClient fake = new FakeCodeClient();
    cmd.codeClientOverride = fake;

    Result r = run(cmd);

    assertThat(r.code()).isZero();
    assertThat(r.out()).contains("Would index").contains("Main.java");
    assertThat(fake.pinged).isFalse();
    assertThat(fake.indexed).isFalse();
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

    OnboardCommand cmd = new OnboardCommand();
    cmd.projectDir = proj;
    cmd.loaderOverride = hermeticLoader(proj);
    cmd.sourceCode = true;
    FakeCodeClient fake = new FakeCodeClient();
    cmd.codeClientOverride = fake;
    FakeConfigClient config = new FakeConfigClient();
    cmd.configClientOverride = config;

    Result r = run(cmd);

    assertThat(r.code()).isZero();
    // The project [discovery] override replaces the defaults: sql in, java out.
    assertThat(fake.body.files()).extracting(CodeIndexRequest.FileDto::repoRelPath)
      .containsExactly("query.sql");
    // The [pieria] overrides were pushed to the profile.
    assertThat(config.putBody).contains("\"weight-graph\":0.0");
    assertThat(r.out()).contains("Pushed project config overrides");
  }

  @Test
  void noOverridesMeansNoConfigPush(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("Main.java"), "class Main {}");

    OnboardCommand cmd = new OnboardCommand();
    cmd.projectDir = proj;
    cmd.loaderOverride = hermeticLoader(proj);
    cmd.sourceCode = true;
    cmd.codeClientOverride = new FakeCodeClient();
    FakeConfigClient config = new FakeConfigClient();
    cmd.configClientOverride = config;

    Result r = run(cmd);

    assertThat(r.code()).isZero();
    assertThat(config.putBody).isNull();
  }

  private record Result(int code, String out) {
  }

  private static final class FakeConfigClient implements dev.alvo.pieria.cli.modules.config.ConfigClient {
    String putBody;

    @Override
    public IngestClient.Reachability ping() {
      return IngestClient.Reachability.OK;
    }

    @Override
    public ConfigResult put(String profile, String overridesJson) {
      this.putBody = overridesJson;
      return new Success("{}");
    }

    @Override
    public ConfigResult get(String profile) {
      return new Success("{}");
    }
  }

  private static final class FakeCodeClient implements CodeIndexClient {
    boolean pinged;
    boolean indexed;
    CodeIndexRequest body;
    CodeIndexResult result = new Success(new CodeIndexResponse(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));

    @Override
    public IngestClient.Reachability ping() {
      pinged = true;
      return IngestClient.Reachability.OK;
    }

    @Override
    public CodeIndexResult index(String profile, CodeIndexRequest request) {
      indexed = true;
      this.body = request;
      return result;
    }
  }
}
