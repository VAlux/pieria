package dev.alvo.pieria.ingestion.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import dev.alvo.pieria.domain.memory.Message;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ClaudeCodeTranscriptParserTests {

  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final ClaudeCodeTranscriptParser parser = new ClaudeCodeTranscriptParser(mapper);

  @Test
  void harnessIdIsClaudeCode() {
    assertThat(parser.harness()).isEqualTo("claude-code");
  }

  @Test
  void keepsUserAndAssistantTurnsInSourceOrder() {
    String transcript = String.join("\n",
      "{\"type\":\"mode\",\"mode\":\"normal\",\"sessionId\":\"abc\"}",
      "{\"type\":\"file-history-snapshot\",\"messageId\":\"x\"}",
      "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"how do hooks work?\"}}",
      "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
        + "{\"type\":\"thinking\",\"thinking\":\"let me think\"},"
        + "{\"type\":\"text\",\"text\":\"They fire on events.\"},"
        + "{\"type\":\"tool_use\",\"name\":\"Read\"}]}}");

    List<Message> messages = parser.parse(transcript, "sess-1");

    assertThat(messages).hasSize(2);
    assertThat(messages.get(0).role()).isEqualTo("user");
    assertThat(messages.get(0).content()).isEqualTo("how do hooks work?");
    assertThat(messages.get(0).sessionId()).isEqualTo("sess-1");
    assertThat(messages.get(1).role()).isEqualTo("assistant");
    // Only the text block survives; thinking/tool_use are dropped.
    assertThat(messages.get(1).content()).isEqualTo("They fire on events.");
  }

  @Test
  void concatenatesMultipleTextBlocks() {
    String transcript = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
      + "{\"type\":\"text\",\"text\":\"first\"},"
      + "{\"type\":\"text\",\"text\":\"second\"}]}}";

    List<Message> messages = parser.parse(transcript, "s");

    assertThat(messages).singleElement()
      .extracting(Message::content)
      .isEqualTo("first\nsecond");
  }

  @Test
  void dropsMetaAndSidechainTurns() {
    String transcript = String.join("\n",
      "{\"type\":\"user\",\"isMeta\":true,\"message\":{\"role\":\"user\",\"content\":\"/compact output\"}}",
      "{\"type\":\"assistant\",\"isSidechain\":true,\"message\":{\"role\":\"assistant\",\"content\":\"subagent chatter\"}}",
      "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"real question\"}}");

    List<Message> messages = parser.parse(transcript, "s");

    assertThat(messages).singleElement()
      .extracting(Message::content)
      .isEqualTo("real question");
  }

  @Test
  void skipsUnparseableLinesAndBlanks() {
    String transcript = String.join("\n",
      "not json at all",
      "",
      "   ",
      "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"survives\"}}");

    List<Message> messages = parser.parse(transcript, "s");

    assertThat(messages).singleElement()
      .extracting(Message::content)
      .isEqualTo("survives");
  }

  @Test
  void dropsTurnsWithNoTextContent() {
    String transcript = String.join("\n",
      "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
        + "{\"type\":\"tool_use\",\"name\":\"Bash\"}]}}",
      "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":["
        + "{\"type\":\"tool_result\",\"content\":\"exit 0\"}]}}");

    List<Message> messages = parser.parse(transcript, "s");

    assertThat(messages).isEmpty();
  }

  @Test
  void returnsEmptyForNullOrBlankTranscript() {
    assertThat(parser.parse(null, "s")).isEmpty();
    assertThat(parser.parse("   ", "s")).isEmpty();
  }
}
