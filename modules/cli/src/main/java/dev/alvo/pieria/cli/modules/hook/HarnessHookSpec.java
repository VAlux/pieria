package dev.alvo.pieria.cli.modules.hook;

import java.util.List;

/**
 * Per-harness constants the hook commands need: which daemon-side transcript parser to select,
 * which environment variables the harness uses to hand over the transcript and session id, and the
 * session-open primer query.
 *
 * <p>These were previously spread across the {@code harness/**} shell scripts. Values that used to
 * be overridable through {@code PIERIA_RECALL_QUERY} / {@code PIERIA_RECALL_LIMIT} are constants
 * here — nothing read them but the scripts themselves.
 *
 * @param id                harness id, doubling as the daemon's transcript-parser key
 * @param transcriptEnvKeys env vars to probe in order for the transcript path
 * @param sessionIdEnvKey   env var carrying the harness session id, or null if it exposes none
 * @param primerQuery       the session-open recall query
 * @param primerLimit       how many memories the session-open primer injects
 */
public record HarnessHookSpec(
  String id,
  List<String> transcriptEnvKeys,
  String sessionIdEnvKey,
  String primerQuery,
  int primerLimit
) {

  private static final String PRIMER_QUERY =
    "What should I know about this project before starting a new session? "
      + "Summarize key facts, active tasks, and recent decisions.";

  public static final HarnessHookSpec CLAUDE_CODE = new HarnessHookSpec(
    "claude-code", List.of("CLAUDE_TRANSCRIPT_PATH"), "CLAUDE_SESSION_ID", PRIMER_QUERY, 10);

  /** Codex sends its transcript path and session id in the command hook's JSON stdin payload. */
  public static final HarnessHookSpec CODEX = new HarnessHookSpec(
    "codex", List.of(), null, PRIMER_QUERY, 10);

  /** OpenCode pipes the transcript on stdin, so it declares no transcript env keys. */
  public static final HarnessHookSpec OPENCODE = new HarnessHookSpec(
    "opencode", List.of(), null, "What should I know about this project?", 10);
}
