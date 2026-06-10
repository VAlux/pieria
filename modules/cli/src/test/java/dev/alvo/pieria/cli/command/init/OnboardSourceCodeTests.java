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

  private record Result(int code, String out) {
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
