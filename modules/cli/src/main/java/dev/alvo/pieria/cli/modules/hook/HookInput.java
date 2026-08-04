package dev.alvo.pieria.cli.modules.hook;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * The common fields a harness sends as one JSON object on a command hook's stdin. Claude Code and
 * Codex use the same field names ({@code session_id}, {@code transcript_path}), so one reader serves
 * both — neither exports the transcript path through the environment.
 */
public record HookInput(String sessionId, Path transcriptPath) {

  /** No payload: every field unset, so callers fall back to the environment. */
  public static final HookInput EMPTY = new HookInput(null, null);

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** Parse the hook payload, leaving absent or null fields unset. */
  public static HookInput read(InputStream input) throws IOException {
    JsonNode root = MAPPER.readTree(input);
    if (root == null || !root.isObject()) {
      throw new IOException("hook input must be a JSON object");
    }
    String sessionId = text(root, "session_id");
    String transcript = text(root, "transcript_path");
    return new HookInput(sessionId, transcript == null ? null : Path.of(transcript));
  }

  /**
   * Parse the hook payload, degrading to {@link #EMPTY} when stdin carries nothing usable. Hooks are
   * fail-closed, and a harness that supplies no payload (or a hand-run {@code pieria hook …}) must
   * still reach the environment fallback rather than abort the ingest.
   */
  public static HookInput readLenient(InputStream input) {
    try {
      return read(input);
    } catch (IOException | RuntimeException e) {
      return EMPTY;
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull() || !value.isString() || value.asString().isBlank()) {
      return null;
    }
    return value.asString();
  }
}
