package dev.alvo.pieria.ingestion.transcript;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Small null-safe helpers shared by the JSONL-based {@link TranscriptParser} implementations for
 * pulling scalars and flattening content-block arrays out of Jackson trees.
 */
final class TranscriptJson {

  private TranscriptJson() {
  }

  /** The string value of {@code field}, or {@code null} if absent/null. */
  static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return (value == null || value.isNull()) ? null : value.asString();
  }

  /** True only if {@code field} is present and a boolean {@code true}. */
  static boolean isTrue(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isBoolean() && value.booleanValue();
  }

  /**
   * Concatenate (newline-separated) the {@code textField} of every block in {@code blocks} whose
   * {@code "type"} is one of {@code acceptedTypes}. Blank text is skipped.
   */
  static String joinTextBlocks(JsonNode blocks, String textField, String... acceptedTypes) {
    Set<String> accepted = Set.of(acceptedTypes);
    StringBuilder sb = new StringBuilder();
    for (JsonNode block : blocks) {
      if (!accepted.contains(text(block, "type"))) {
        continue;
      }
      String value = text(block, textField);
      if (value != null && !value.isBlank()) {
        if (!sb.isEmpty()) {
          sb.append('\n');
        }
        sb.append(value);
      }
    }
    return sb.toString();
  }
}
