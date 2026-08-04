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

  // Developer turns are the sandbox/collaboration/multi-agent preamble Codex prepends to every
  // session — harness configuration, never dialogue.
  @Test
  void dropsDeveloperAndOtherNonConversationRoles() {
    String transcript = String.join("\n",
      message("developer", "<permissions instructions> Filesystem sandboxing defines which files..."),
      message("developer", "<multi_agent_mode>Do not spawn sub-agents unless...</multi_agent_mode>"),
      message("system", "you are a helpful assistant"),
      message("user", "add a toggle"));

    assertThat(parser.parse(transcript, "s"))
      .singleElement()
      .extracting(Message::content)
      .isEqualTo("add a toggle");
  }

  // The bug this filter exists for: the AGENTS.md dump arrives under the *user* role at the head of
  // every session, so without it the harness's own instructions are re-extracted every time.
  @Test
  void dropsTheInjectedAgentsInstructionsTurn() {
    String transcript = String.join("\n",
      message("user", "# AGENTS.md instructions for /Users/dev/projects/app\\n\\n<INSTRUCTIONS>\\n"
        + "# Persistent Knowledge\\n- Treat the daemon as the primary knowledge base.\\n</INSTRUCTIONS>"),
      message("user", "now fix the parser"));

    assertThat(parser.parse(transcript, "s"))
      .singleElement()
      .extracting(Message::content)
      .isEqualTo("now fix the parser");
  }

  @Test
  void dropsTheSyntheticUserEnvelopesCodexInjects() {
    String transcript = String.join("\n",
      message("user", "<environment_context>\\n  <cwd>/w</cwd>\\n</environment_context>"),
      message("user", "<turn_aborted>\\nThe user interrupted the previous turn on purpose.\\n</turn_aborted>"),
      message("user", "<skill>\\n<name>skill-creator</name>\\n<path>/s/SKILL.md</path>"),
      message("user", "<user_action>\\n  <action>review</action>\\n</user_action>"),
      message("user", "<user_shell_command>\\n<command>\\ngs\\n</command>\\n</user_shell_command>"),
      message("user", "<subagent_notification>\\n{\\\"agent_path\\\":\\\"019d\\\"}"),
      message("user", "real question"));

    assertThat(parser.parse(transcript, "s"))
      .singleElement()
      .extracting(Message::content)
      .isEqualTo("real question");
  }

  // Replayed history was already ingested by the session that produced it; re-ingesting mints
  // duplicates, because memory ids are scoped by session.
  @Test
  void dropsReplayedHistoryHandedToReviewAndSubagentTurns() {
    String transcript = String.join("\n",
      message("user", "The following is the Codex agent history whose request action you are "
        + "assessing.\\n>>> TRANSCRIPT START\\n[1] user: write the spec"),
      message("user", "A previous agent produced the plan below to accomplish the user's task.\\n\\n"
        + "## Unified Diagnostic Pipeline"),
      message("assistant", "on it"));

    assertThat(parser.parse(transcript, "s"))
      .singleElement()
      .extracting(Message::content)
      .isEqualTo("on it");
  }

  // Prefix matching is deliberately anchored: a human writing *about* these things is still dialogue.
  @Test
  void keepsUserTurnsThatOnlyMentionAnEnvelopeSubject() {
    String transcript = String.join("\n",
      message("user", "Generate a file named AGENTS.md that serves as a contributor guide"),
      message("user", "why does the parser drop <environment_context> blocks?"));

    assertThat(parser.parse(transcript, "s"))
      .extracting(Message::content)
      .containsExactly(
        "Generate a file named AGENTS.md that serves as a contributor guide",
        "why does the parser drop <environment_context> blocks?");
  }

  private String message(String role, String text) {
    return "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"" + role
      + "\",\"content\":[{\"type\":\"input_text\",\"text\":\"" + text + "\"}]}}";
  }
}
