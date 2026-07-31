package dev.alvo.pieria.cli.modules.hook;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodexHookInputTests {

  @Test
  void readsCommonFieldsFromJsonStdin() throws IOException {
    CodexHookInput input = read("""
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

  @Test
  void acceptsAbsentOrNullOptionalFields() throws IOException {
    assertThat(read("{}")).isEqualTo(new CodexHookInput(null, null));
    assertThat(read("{\"session_id\":null,\"transcript_path\":null}"))
      .isEqualTo(new CodexHookInput(null, null));
  }

  @Test
  void rejectsMalformedOrNonObjectInput() {
    assertThatThrownBy(() -> read("not-json")).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> read("[]"))
      .isInstanceOf(IOException.class)
      .hasMessageContaining("JSON object");
  }

  private CodexHookInput read(String json) throws IOException {
    return CodexHookInput.read(
      new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
  }
}
