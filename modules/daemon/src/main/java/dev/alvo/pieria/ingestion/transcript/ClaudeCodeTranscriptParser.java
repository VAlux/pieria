package dev.alvo.pieria.ingestion.transcript;

import dev.alvo.pieria.domain.memory.Message;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link TranscriptParser} for Claude Code session transcripts (JSONL — one event object per line).
 *
 * <p>Claude Code writes a stream of typed events, not a flat {@code messages[]} array. Only
 * {@code user} and {@code assistant} events carry conversation turns; everything else (mode changes,
 * hook results, file snapshots, system events) is skipped. Meta turns ({@code isMeta:true}, e.g.
 * injected slash-command output) and sidechain turns ({@code isSidechain:true}, i.e. subagent
 * conversations) are dropped so only the primary human/assistant dialogue is ingested.
 *
 * <p>A turn's {@code message.content} is either a plain string or an array of typed blocks; only
 * {@code text} blocks contribute. {@code thinking}, {@code tool_use}, and {@code tool_result} blocks
 * are noise for memory extraction and are ignored.
 */
@Component
public class ClaudeCodeTranscriptParser implements TranscriptParser {

  private final ObjectMapper objectMapper;

  public ClaudeCodeTranscriptParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public String harness() {
    return "claude-code";
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

      String type = TranscriptJson.text(node, "type");
      if (!"user".equals(type) && !"assistant".equals(type)) {
        continue;
      }
      if (TranscriptJson.isTrue(node, "isMeta") || TranscriptJson.isTrue(node, "isSidechain")) {
        continue;
      }

      JsonNode message = node.get("message");
      if (message == null || message.isNull()) {
        continue;
      }
      String role = TranscriptJson.text(message, "role");
      if (role == null || role.isBlank()) {
        role = type;
      }
      String content = extractContent(message.get("content"));
      if (content.isBlank()) {
        continue;
      }
      messages.add(Message.of(sessionId, role, content));
    }
    return messages;
  }

  /**
   * Flatten a {@code message.content} node: a string node as-is, or the concatenation of the
   * {@code text} blocks of an array node. Non-text blocks and null nodes yield no text.
   */
  private String extractContent(JsonNode content) {
    if (content == null || content.isNull()) {
      return "";
    }
    if (content.isArray()) {
      return TranscriptJson.joinTextBlocks(content, "text", "text");
    }
    return content.asString();
  }
}
