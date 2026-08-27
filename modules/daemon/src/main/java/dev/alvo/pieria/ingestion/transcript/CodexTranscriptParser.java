package dev.alvo.pieria.ingestion.transcript;

import dev.alvo.pieria.domain.memory.Message;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@link TranscriptParser} for Codex CLI rollout transcripts (JSONL — one event object per line).
 *
 * <p>Codex wraps every event as {@code {timestamp, type, payload}}. Conversation turns are
 * {@code type == "response_item"} events whose {@code payload.type == "message"}; the payload carries
 * {@code role} and a {@code content} array of blocks ({@code input_text} for user turns,
 * {@code output_text} for assistant turns). All other events (session_meta, event_msg, reasoning,
 * function_call/output, token_count, …) are skipped, as are streaming {@code event_msg} echoes so a
 * turn is not counted twice.
 *
 * <p>Only {@code user} and {@code assistant} turns are conversation. {@code developer} messages are
 * harness scaffolding (sandbox permissions, collaboration/multi-agent mode, skill instructions) and
 * never carry durable signal.
 *
 * <p>Codex also injects synthetic turns under the {@code user} role — the repo's AGENTS.md dump, the
 * environment context block, replayed history handed to a review or subagent turn — which are not
 * things the user said. Left in, they are re-extracted on every session and dominate the store with
 * near-duplicate memories about the harness itself. This mirrors what
 * {@link ClaudeCodeTranscriptParser} gets from {@code isMeta}/{@code isSidechain}; Codex marks
 * nothing, so the envelopes are recognized by their opening text.
 */
@Component
public class CodexTranscriptParser implements TranscriptParser {

  /**
   * Roles that carry actual dialogue. Everything else is harness scaffolding.
   */
  private static final Set<String> CONVERSATION_ROLES = Set.of("user", "assistant");

  /**
   * Openers of the synthetic {@code user} turns Codex injects. Matched against the stripped start of
   * a message, so a turn where the user genuinely writes about AGENTS.md is unaffected.
   */
  private static final List<String> INJECTED_TURN_PREFIXES = List.of(
    "# AGENTS.md instructions",
    "<environment_context>",
    "<turn_aborted>",
    "<skill>",
    "<user_action>",
    "<user_shell_command>",
    "<subagent_notification>",
    // Replayed transcripts handed to a review/assessment or subagent turn: already ingested in the
    // session that produced them, and re-ingesting mints duplicates under the new session id.
    "The following is the Codex agent history",
    "A previous agent produced the plan below");

  private final ObjectMapper objectMapper;

  public CodexTranscriptParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Whether a turn is one of the envelopes Codex injects rather than something a human wrote.
   */
  private static boolean isInjectedTurn(String text) {
    String head = text.stripLeading();
    return INJECTED_TURN_PREFIXES.stream().anyMatch(head::startsWith);
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
      if (role == null || !CONVERSATION_ROLES.contains(role)) {
        continue;
      }
      JsonNode content = payload.get("content");
      if (content == null || !content.isArray()) {
        continue;
      }
      String text = TranscriptJson.joinTextBlocks(content, "text", "input_text", "output_text");
      if (text.isBlank() || isInjectedTurn(text)) {
        continue;
      }
      messages.add(Message.of(sessionId, role, text));
    }
    return messages;
  }
}
