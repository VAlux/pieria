package dev.alvo.pieria.ingestion.transcript;

import static org.assertj.core.api.Assertions.assertThat;

import dev.alvo.pieria.domain.memory.Message;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class CodexTranscriptParserTests {

  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final CodexTranscriptParser parser = new CodexTranscriptParser(mapper);

  @Test
  void harnessIdIsCodex() {
    assertThat(parser.harness()).isEqualTo("codex");
  }

  @Test
  void extractsMessagesFromResponseItemsAndSkipsOtherEvents() {
    String transcript = String.join("\n",
      "{\"timestamp\":\"t\",\"type\":\"session_meta\",\"payload\":{\"id\":\"x\"}}",
      "{\"timestamp\":\"t\",\"type\":\"event_msg\",\"payload\":{\"type\":\"task_started\"}}",
      "{\"timestamp\":\"t\",\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\","
        + "\"content\":[{\"type\":\"input_text\",\"text\":\"add a toggle\"}]}}",
      "{\"timestamp\":\"t\",\"type\":\"response_item\",\"payload\":{\"type\":\"reasoning\",\"summary\":[]}}",
      "{\"timestamp\":\"t\",\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"assistant\","
        + "\"content\":[{\"type\":\"output_text\",\"text\":\"done.\"}],\"phase\":\"commentary\"}}");

    List<Message> messages = parser.parse(transcript, "codex-1");

    assertThat(messages).hasSize(2);
    assertThat(messages.get(0).role()).isEqualTo("user");
    assertThat(messages.get(0).content()).isEqualTo("add a toggle");
    assertThat(messages.get(0).sessionId()).isEqualTo("codex-1");
    assertThat(messages.get(1).role()).isEqualTo("assistant");
    assertThat(messages.get(1).content()).isEqualTo("done.");
  }

  @Test
  void dropsFunctionCallsAndTextlessMessages() {
    String transcript = String.join("\n",
      "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call\",\"name\":\"shell\"}}",
      "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call_output\",\"output\":\"ok\"}}",
      "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"assistant\","
        + "\"content\":[{\"type\":\"image\",\"url\":\"x\"}]}}");

    assertThat(parser.parse(transcript, "s")).isEmpty();
  }

  @Test
  void skipsUnparseableLinesAndBlanks() {
    String transcript = String.join("\n",
      "garbage",
      "",
      "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\","
        + "\"content\":[{\"type\":\"input_text\",\"text\":\"survives\"}]}}");

    assertThat(parser.parse(transcript, "s"))
      .singleElement()
      .extracting(Message::content)
      .isEqualTo("survives");
  }

  @Test
  void returnsEmptyForNullOrBlankTranscript() {
    assertThat(parser.parse(null, "s")).isEmpty();
    assertThat(parser.parse("   ", "s")).isEmpty();
  }
}
