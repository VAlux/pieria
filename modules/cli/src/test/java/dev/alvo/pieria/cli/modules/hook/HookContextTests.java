package dev.alvo.pieria.cli.modules.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HookContextTests {

  private HookContext context(Map<String, String> env, Path workingDir) {
    return new HookContext(env::get, workingDir, "claude-code");
  }

  @Test
  void profileComesFromPieriaProfileEnvVar(@TempDir Path tmp) {
    HookContext ctx = context(Map.of("PIERIA_PROFILE", "My Repo"), tmp);
    assertThat(ctx.profile()).isEqualTo("my-repo");
  }

  @Test
  void profileFallsBackToDirectoryNameWhenNoOverride(@TempDir Path tmp) throws IOException {
    Path dir = Files.createDirectories(tmp.resolve("Some_Project"));
    HookContext ctx = context(Map.of(), dir);
    assertThat(ctx.profile()).isEqualTo("some-project");
  }

  @Test
  void daemonUrlDefaultsToLocalhostAndHonoursEnvOverride(@TempDir Path tmp) {
    assertThat(context(Map.of(), tmp).daemonUrl()).isEqualTo("http://127.0.0.1:8077");
    assertThat(context(Map.of("PIERIA_DAEMON_URL", "http://127.0.0.1:9999"), tmp).daemonUrl())
      .isEqualTo("http://127.0.0.1:9999");
  }

  @Test
  void transcriptResolvesFirstEnvKeyThatPointsAtAnExistingFile(@TempDir Path tmp) throws IOException {
    Path real = Files.writeString(tmp.resolve("rollout.jsonl"), "{}\n");
    HarnessHookSpec spec = new HarnessHookSpec(
      "test", List.of("FIRST_TRANSCRIPT", "SECOND_TRANSCRIPT"), null);
    HookContext ctx = new HookContext(
      Map.of(
        "FIRST_TRANSCRIPT", tmp.resolve("missing.jsonl").toString(),
        "SECOND_TRANSCRIPT", real.toString())::get,
      tmp, "test");

    assertThat(ctx.firstExistingTranscript(spec)).contains(real);
  }

  @Test
  void transcriptIsEmptyWhenNoCandidateExists(@TempDir Path tmp) throws IOException {
    Path real = Files.writeString(tmp.resolve("rollout.jsonl"), "{}\n");
    HarnessHookSpec spec = new HarnessHookSpec("test", List.of("ONLY_TRANSCRIPT"), null);

    assertThat(new HookContext(Map.of("ONLY_TRANSCRIPT", real.toString())::get, tmp, "test")
      .firstExistingTranscript(spec)).contains(real);
    assertThat(new HookContext(
      Map.of("ONLY_TRANSCRIPT", tmp.resolve("nope.jsonl").toString())::get, tmp, "test")
      .firstExistingTranscript(spec)).isEmpty();
  }

  // Claude Code and Codex both hand the transcript over on stdin, so neither declares an env key.
  @Test
  void harnessesThatSendTheTranscriptOnStdinDeclareNoTranscriptEnvKey(@TempDir Path tmp) {
    assertThat(HarnessHookSpec.CLAUDE_CODE.transcriptEnvKeys()).isEmpty();
    assertThat(HarnessHookSpec.CODEX.transcriptEnvKeys()).isEmpty();
    assertThat(context(Map.of(), tmp).firstExistingTranscript(HarnessHookSpec.CLAUDE_CODE)).isEmpty();
  }

  @Test
  void sessionIdReadsTheHarnessEnvKeyAndIsNullWhenAbsent(@TempDir Path tmp) {
    assertThat(context(Map.of("CLAUDE_CODE_SESSION_ID", "abc"), tmp)
      .sessionId(HarnessHookSpec.CLAUDE_CODE)).isEqualTo("abc");
    assertThat(context(Map.of(), tmp).sessionId(HarnessHookSpec.CLAUDE_CODE)).isNull();
  }

  @Test
  void envLookupTreatsBlankAsAbsent(@TempDir Path tmp) {
    HookContext ctx = context(Map.of("SOME_KEY", "   "), tmp);
    assertThat(ctx.env("SOME_KEY")).isEmpty();
    assertThat(ctx.env("MISSING")).isEqualTo(Optional.empty());
  }
}
