package dev.alvo.pieria.cli.modules.hook;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HookInputTests {

  @Test
  void readsCommonFieldsFromJsonStdin() throws IOException {
    HookInput input = read("""
      {
        "session_id": "thr_123",
        "transcript_path": "/workspace/rollout.jsonl",
        "cwd": "/workspace",
        "hook_event_name": "Stop"
      }
      """);

    assertThat(input.sessionId()).isEqualTo("thr_123");
    assertThat(input.transcriptPath()).isEqualTo(Path.of("/workspace/rollout.jsonl"));
  }

  // Claude Code sends the same field names as Codex, which is why one reader serves both.
  @Test
  void readsTheClaudeCodePayloadShape() throws IOException {
    HookInput input = read("""
      {
        "session_id": "3eb984a9-8e28-4ff9-9738-6fdbc04bb69e",
        "transcript_path": "/home/u/.claude/projects/-home-u-proj/3eb984a9.jsonl",
        "cwd": "/home/u/proj",
        "hook_event_name": "SessionEnd"
      }
      """);

    assertThat(input.sessionId()).isEqualTo("3eb984a9-8e28-4ff9-9738-6fdbc04bb69e");
    assertThat(input.transcriptPath())
      .isEqualTo(Path.of("/home/u/.claude/projects/-home-u-proj/3eb984a9.jsonl"));
  }

  @Test
  void acceptsAbsentOrNullOptionalFields() throws IOException {
    assertThat(read("{}")).isEqualTo(HookInput.EMPTY);
    assertThat(read("{\"session_id\":null,\"transcript_path\":null}")).isEqualTo(HookInput.EMPTY);
  }

  @Test
  void rejectsMalformedOrNonObjectInput() {
    assertThatThrownBy(() -> read("not-json")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> read("[]"))
      .isInstanceOf(IOException.class)
      .hasMessageContaining("JSON object");
  }

  // Hooks are fail-closed: unusable stdin must fall through to the environment, not abort the ingest.
  @Test
  void lenientReadDegradesToEmptyInsteadOfThrowing() {
    assertThat(HookInput.readLenient(stream("not-json"))).isEqualTo(HookInput.EMPTY);
    assertThat(HookInput.readLenient(stream("[]"))).isEqualTo(HookInput.EMPTY);
    assertThat(HookInput.readLenient(stream(""))).isEqualTo(HookInput.EMPTY);
    assertThat(HookInput.readLenient(stream("{\"session_id\":\"s1\"}")))
      .isEqualTo(new HookInput("s1", null, null, null, null, null));
  }

  @Test
  void postToolUseFieldsAreParsed() {
    HookInput input = HookInput.readLenient(new java.io.ByteArrayInputStream("""
      {"session_id":"s1","tool_name":"Bash",
       "tool_input":{"command":"./gradlew test"},
       "tool_response":{"stdout":"BUILD FAILED","exitCode":1}}
      """.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    assertThat(input.sessionId()).isEqualTo("s1");
    assertThat(input.toolName()).isEqualTo("Bash");
    assertThat(input.toolInput()).contains("./gradlew test");
    assertThat(input.toolResponse()).contains("BUILD FAILED");
    assertThat(input.exitCode()).isEqualTo(1);
  }

  // The lifecycle hooks must keep working: their payload has none of these fields.
  @Test
  void lifecyclePayloadsLeavePostToolUseFieldsUnset() {
    HookInput input = HookInput.readLenient(new java.io.ByteArrayInputStream("""
      {"session_id":"s1","transcript_path":"/tmp/t.jsonl"}
      """.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    assertThat(input.toolName()).isNull();
    assertThat(input.toolInput()).isNull();
    assertThat(input.exitCode()).isNull();
  }

  private HookInput read(String json) throws IOException {
    return HookInput.read(stream(json));
  }

  private InputStream stream(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
  }
}
