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
public record HookInput(String sessionId,
                        Path transcriptPath,
                        String toolName,
                        String toolInput,
                        String toolResponse,
                        Integer exitCode) {

  /** No payload: every field unset, so callers fall back to the environment. */
  public static final HookInput EMPTY = new HookInput(null, null, null, null, null, null);

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /**
   * Parse the hook payload, leaving absent or null fields unset.
   *
   * <p>{@code tool_name}/{@code tool_input}/{@code tool_response} are the PostToolUse fields; the
   * lifecycle hooks send none of them, and reading one reader for both keeps the two payload
   * shapes from needing separate parsers. {@code tool_input} and {@code tool_response} are kept as
   * raw JSON text because their shape varies per tool.
   */
  public static HookInput read(InputStream input) throws IOException {
    JsonNode root = MAPPER.readTree(input);
    if (root == null || !root.isObject()) {
      throw new IOException("hook input must be a JSON object");
    }
    String transcript = text(root, "transcript_path");
    return new HookInput(
      text(root, "session_id"),
      transcript == null ? null : Path.of(transcript),
      text(root, "tool_name"),
      raw(root, "tool_input"),
      raw(root, "tool_response"),
      exitCode(root.get("tool_response")));
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

  /** A child node as raw JSON text, or null when absent. */
  private static String raw(JsonNode root, String field) {
    JsonNode value = root.get(field);
    return value == null || value.isNull() ? null : value.toString();
  }

  /**
   * The tool response's exit code, under either spelling harnesses use. Absent for tools that do
   * not run a process, which is not an error.
   */
  private static Integer exitCode(JsonNode toolResponse) {
    if (toolResponse == null || !toolResponse.isObject()) {
      return null;
    }
    for (String field : new String[] {"exitCode", "exit_code", "returnCode"}) {
      JsonNode value = toolResponse.get(field);
      if (value != null && value.isNumber()) {
        return value.intValue();
      }
    }
    return null;
  }
}
