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
 * @param transcriptEnvKeys env vars to probe in order for the transcript path, as a fallback behind
 *                          the hook's JSON stdin payload; empty when the harness exports none
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

  /**
   * The session-open recall query, shared by every harness.
   *
   * <p>Phrased in the vocabulary of a <em>codebase</em>, never that of the memory system. The
   * previous wording ("key facts, active tasks, and recent decisions") was a near-perfect lexical
   * match for memories describing Pieria itself — its own standing instructions talk about durable
   * facts, project context, decisions and sessions — so FTS ranked those first and the primer
   * reliably injected the memory system's configuration instead of anything about the project. On a
   * real profile it returned ten such memories and nothing else.
   *
   * <p>Keep it that way: no {@code fact}/{@code decision}/{@code task}/{@code session}/
   * {@code memory}/{@code context} vocabulary, which {@code PrimerQueryTests} pins.
   */
  private static final String PRIMER_QUERY =
    "architecture, module responsibilities, build and test commands, coding conventions, "
      + "and known pitfalls of this codebase";

  /**
   * Claude Code sends its transcript path and session id in the hook's JSON stdin payload. It
   * exports no {@code CLAUDE_TRANSCRIPT_PATH}, so there is no transcript env key to probe;
   * {@code CLAUDE_CODE_SESSION_ID} is the session id it does export, and is the fallback for a
   * payload that omits it.
   */
  public static final HarnessHookSpec CLAUDE_CODE = new HarnessHookSpec(
    "claude-code", List.of(), "CLAUDE_CODE_SESSION_ID", PRIMER_QUERY, 10);

  /** Codex sends its transcript path and session id in the command hook's JSON stdin payload. */
  public static final HarnessHookSpec CODEX = new HarnessHookSpec(
    "codex", List.of(), null, PRIMER_QUERY, 10);

  /**
   * OpenCode pipes the transcript on stdin, so it declares no transcript env keys. It used to carry
   * its own, even vaguer primer query; there is no reason for the harnesses to prime differently.
   */
  public static final HarnessHookSpec OPENCODE = new HarnessHookSpec(
    "opencode", List.of(), null, PRIMER_QUERY, 10);
}
