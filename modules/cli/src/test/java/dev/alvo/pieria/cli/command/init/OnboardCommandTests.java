package dev.alvo.pieria.cli.command.init;

import dev.alvo.pieria.api.request.IngestRequest;
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

class OnboardCommandTests {

  private static void writeReadme(Path proj) throws IOException {
    Files.writeString(proj.resolve("README.md"), "# Project\nSome durable knowledge.");
  }

  /** Confine config loading to the temp project so tests never read the real OS config dir. */
  private static dev.alvo.pieria.cli.modules.config.ProjectConfigLoader hermeticLoader(Path proj) {
    return new dev.alvo.pieria.cli.modules.config.ProjectConfigLoader(
      proj.resolve("global-config.toml"), proj.resolve(".pieria").resolve("config.toml"));
  }

  /**
   * Run InitCommand with a fake client and captured stdout/stderr.
   */
  private static Result run(OnboardCommand cmd, FakeClient fake) {
    cmd.clientOverride = fake;
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
  void dryRunReportsDocsAndNeverContactsDaemon(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    OnboardCommand cmd = new OnboardCommand();
    cmd.projectDir = proj;
    cmd.loaderOverride = hermeticLoader(proj);
    cmd.dryRun = true;
    FakeClient fake = new FakeClient();

    Result r = run(cmd, fake);

    assertThat(r.code()).isZero();
    assertThat(r.out()).contains("Would seed").contains("README.md");
    assertThat(fake.pinged).isFalse();
    assertThat(fake.ingested).isFalse();
  }

  @Test
  void emptyDirSucceedsWithoutContactingDaemon(@TempDir Path proj) {
    OnboardCommand cmd = new OnboardCommand();
    cmd.projectDir = proj;
    cmd.loaderOverride = hermeticLoader(proj);
    FakeClient fake = new FakeClient();

    Result r = run(cmd, fake);

    assertThat(r.code()).isZero();
    assertThat(r.out()).contains("nothing to seed");
    assertThat(fake.ingested).isFalse();
  }

  @Test
  void successReportsStoredCount(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    OnboardCommand cmd = new OnboardCommand();
    cmd.projectDir = proj;
    cmd.loaderOverride = hermeticLoader(proj);
    FakeClient fake = new FakeClient();
    fake.result = new IngestClient.Success(5);

    Result r = run(cmd, fake);

    assertThat(r.code()).isZero();
    assertThat(r.out()).contains("Stored 5 memories");
  }

  @Test
  void daemonDownOnPingReturnsExit3(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    OnboardCommand cmd = new OnboardCommand();
    cmd.projectDir = proj;
    cmd.loaderOverride = hermeticLoader(proj);
    FakeClient fake = new FakeClient();
    fake.reachability = IngestClient.Reachability.DAEMON_DOWN;

    Result r = run(cmd, fake);

    assertThat(r.code()).isEqualTo(3);
    assertThat(r.err()).contains("not reachable");
    assertThat(fake.ingested).isFalse();
  }

  @Test
  void modelUnavailableReturnsExit4(@TempDir Path proj) throws IOException {
    writeReadme(proj);
    OnboardCommand cmd = new OnboardCommand();
    cmd.projectDir = proj;
    cmd.loaderOverride = hermeticLoader(proj);
    FakeClient fake = new FakeClient();
    fake.result = new IngestClient.ModelUnavailable();

    Result r = run(cmd, fake);

    assertThat(r.code()).isEqualTo(4);
    assertThat(r.err()).contains("model provider");
  }

  /**
   * Fake client with scripted reachability + ingest results; records whether it was contacted.
   */
  private static final class FakeClient implements IngestClient {
    Reachability reachability = Reachability.OK;
    IngestResult result = new Success(0);
    boolean pinged = false;
    boolean ingested = false;

    @Override
    public Reachability ping() {
      pinged = true;
      return reachability;
    }

    @Override
    public IngestResult ingest(String profile, IngestRequest body) {
      ingested = true;
      return result;
    }
  }

  private record Result(int code, String out, String err) {
  }
}
