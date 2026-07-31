package dev.alvo.pieria.cli.modules.hook;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** The common fields Codex sends as one JSON object on a command hook's stdin. */
public record CodexHookInput(String sessionId, Path transcriptPath) {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** Parse the Codex hook payload, leaving absent or null fields unset. */
  public static CodexHookInput read(InputStream input) throws IOException {
    JsonNode root = MAPPER.readTree(input);
    if (root == null || !root.isObject()) {
      throw new IOException("Codex hook input must be a JSON object");
    }
    String sessionId = text(root, "session_id");
    String transcript = text(root, "transcript_path");
    return new CodexHookInput(sessionId, transcript == null ? null : Path.of(transcript));
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull() || !value.isString() || value.asString().isBlank()) {
      return null;
    }
    return value.asString();
  }
}
