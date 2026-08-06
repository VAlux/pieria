package dev.alvo.pieria.cli.modules.hook;

import java.util.List;

/**
 * Per-harness constants the hook commands need: which daemon-side transcript parser to select, and
 * which environment variables the harness uses to hand over the transcript and session id.
 *
 * <p>These were previously spread across the {@code harness/**} shell scripts. Values that used to
 * be overridable through {@code PIERIA_RECALL_QUERY} / {@code PIERIA_RECALL_LIMIT} were folded in
 * here as constants — nothing read them but the scripts themselves — and have since been dropped
 * along with the session-open primer recall they configured. Session start now emits a pointer at
 * the store rather than a guess at its contents; see {@link MemoryPointer}.
 *
 * @param id                harness id, doubling as the daemon's transcript-parser key
 * @param transcriptEnvKeys env vars to probe in order for the transcript path, as a fallback behind
 *                          the hook's JSON stdin payload; empty when the harness exports none
 * @param sessionIdEnvKey   env var carrying the harness session id, or null if it exposes none
 */
public record HarnessHookSpec(
  String id,
  List<String> transcriptEnvKeys,
  String sessionIdEnvKey
) {

  /**
   * Claude Code sends its transcript path and session id in the hook's JSON stdin payload. It
   * exports no {@code CLAUDE_TRANSCRIPT_PATH}, so there is no transcript env key to probe;
   * {@code CLAUDE_CODE_SESSION_ID} is the session id it does export, and is the fallback for a
   * payload that omits it.
   */
  public static final HarnessHookSpec CLAUDE_CODE = new HarnessHookSpec(
    "claude-code", List.of(), "CLAUDE_CODE_SESSION_ID");

  /** Codex sends its transcript path and session id in the command hook's JSON stdin payload. */
  public static final HarnessHookSpec CODEX = new HarnessHookSpec("codex", List.of(), null);

  /** OpenCode pipes the transcript on stdin, so it declares no transcript env keys. */
  public static final HarnessHookSpec OPENCODE = new HarnessHookSpec("opencode", List.of(), null);
}
