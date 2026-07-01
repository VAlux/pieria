package dev.alvo.pieria.ingestion.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.alvo.pieria.domain.memory.Message;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class TranscriptParserRegistryTests {

  private final ObjectMapper mapper = JsonMapper.builder().build();

  private TranscriptParserRegistry registry() {
    return new TranscriptParserRegistry(List.of(
      new ClaudeCodeTranscriptParser(mapper),
      new CodexTranscriptParser(mapper)));
  }

  @Test
  void dispatchesByHarnessId() {
    TranscriptParserRegistry registry = registry();

    assertThat(registry.forHarness("claude-code")).isInstanceOf(ClaudeCodeTranscriptParser.class);
    assertThat(registry.forHarness("codex")).isInstanceOf(CodexTranscriptParser.class);
    assertThat(registry.supportedHarnesses()).containsExactlyInAnyOrder("claude-code", "codex");
  }

  @Test
  void unknownHarnessIsRejectedWithSupportedList() {
    assertThatThrownBy(() -> registry().forHarness("opencode"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("opencode")
      .hasMessageContaining("claude-code")
      .hasMessageContaining("codex");
  }

  @Test
  void rejectsDuplicateHarnessRegistration() {
    assertThatThrownBy(() -> new TranscriptParserRegistry(List.of(
      new ClaudeCodeTranscriptParser(mapper),
      new ClaudeCodeTranscriptParser(mapper))))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("claude-code");
  }

  @Test
  void selectedParserProducesMessages() {
    List<Message> messages = registry().forHarness("codex").parse(
      "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\","
        + "\"content\":[{\"type\":\"input_text\",\"text\":\"hi\"}]}}",
      "s");

    assertThat(messages).singleElement().extracting(Message::content).isEqualTo("hi");
  }
}
