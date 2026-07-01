package dev.alvo.pieria.ingestion.transcript;

import dev.alvo.pieria.domain.memory.Message;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link TranscriptParser} for Codex CLI rollout transcripts (JSONL — one event object per line).
 *
 * <p>Codex wraps every event as {@code {timestamp, type, payload}}. Conversation turns are
 * {@code type == "response_item"} events whose {@code payload.type == "message"}; the payload carries
 * {@code role} and a {@code content} array of blocks ({@code input_text} for user turns,
 * {@code output_text} for assistant turns). All other events (session_meta, event_msg, reasoning,
 * function_call/output, token_count, …) are skipped, as are streaming {@code event_msg} echoes so a
 * turn is not counted twice.
 */
@Component
public class CodexTranscriptParser implements TranscriptParser {

  private final ObjectMapper objectMapper;

  public CodexTranscriptParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public String harness() {
    return "codex";
  }

  @Override
  public List<Message> parse(String transcript, String sessionId) {
    if (transcript == null || transcript.isBlank()) {
      return List.of();
    }
    List<Message> messages = new ArrayList<>();
    for (String line : transcript.split("\n")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty()) {
        continue;
      }
      JsonNode node;
      try {
        node = objectMapper.readTree(trimmed);
      } catch (JacksonException ex) {
        // Skip unparseable lines rather than failing the whole transcript.
        continue;
      }

      if (!"response_item".equals(TranscriptJson.text(node, "type"))) {
        continue;
      }
      JsonNode payload = node.get("payload");
      if (payload == null || !"message".equals(TranscriptJson.text(payload, "type"))) {
        continue;
      }
      String role = TranscriptJson.text(payload, "role");
      if (role == null || role.isBlank()) {
        continue;
      }
      JsonNode content = payload.get("content");
      if (content == null || !content.isArray()) {
        continue;
      }
      String text = TranscriptJson.joinTextBlocks(content, "text", "input_text", "output_text");
      if (text.isBlank()) {
        continue;
      }
      messages.add(Message.of(sessionId, role, text));
    }
    return messages;
  }
}
