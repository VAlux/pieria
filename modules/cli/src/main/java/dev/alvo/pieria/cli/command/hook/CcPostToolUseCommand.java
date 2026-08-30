package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.cli.modules.hook.HookInput;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;
import dev.alvo.pieria.cli.modules.hook.TraceSpool;
import dev.alvo.pieria.tools.Redaction;
import picocli.CommandLine.Command;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Claude Code {@code PostToolUse}: record one tool call.
 *
 * <p>This runs after <em>every</em> tool call, inside the agent's loop, so it does exactly two
 * things — scrub and append a line — and never contacts the daemon. The turn-end hooks ship the
 * batch.
 */
@Command(name = "post-tool-use", description = "Claude Code PostToolUse hook.")
public final class CcPostToolUseCommand extends AbstractHookCommand {

  /**
   * Per-field cap applied here rather than daemon-side. The daemon re-applies its configured
   * budget, but the hook cannot read daemon config without a request it must not make, and an
   * uncapped write is what would make this hook slow.
   */
  private static final int CAPTURE_BUDGET_CHARS = 4000;

  @Override
  protected HookOutcome execute() {
    HookInput input = HookInput.readLenient(System.in);
    if (input.toolName() == null || input.toolName().isBlank()) {
      return new HookOutcome.Skipped("no tool_name in the PostToolUse payload; nothing to record");
    }

    Path repoRoot = Path.of("").toAbsolutePath();
    Path userHome = Path.of(System.getProperty("user.home", "")).toAbsolutePath();
    Instant now = Instant.now();

    TraceEventDto event = new TraceEventDto(
      input.toolName(),
      scrub(input.toolInput(), repoRoot, userHome),
      scrub(input.toolResponse(), repoRoot, userHome),
      status(input.exitCode()),
      input.exitCode(),
      null,
      null,
      now);

    new TraceSpool(TraceSpool.defaultRoot()).append(input.sessionId(), event);
    return HookOutcome.ok();
  }

  /**
   * Truncate first, then redact — that ordering bounds the work by the budget instead of by raw
   * output size, which is what keeps this off the critical path. A secret past the budget is
   * discarded rather than scanned, and never reaches disk either way.
   */
  private static String scrub(String text, Path repoRoot, Path userHome) {
    return text == null ? null
      : Redaction.scrub(text, CAPTURE_BUDGET_CHARS, repoRoot, userHome).text();
  }

  /** A missing exit code means the tool ran no process, which is not the same as succeeding. */
  private static TraceStatus status(Integer exitCode) {
    if (exitCode == null) {
      return TraceStatus.UNKNOWN;
    }
    return exitCode == 0 ? TraceStatus.SUCCESS : TraceStatus.FAILURE;
  }

  @Override
  protected String label() {
    return "pieria/claude-code-post-tool-use";
  }
}
