package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.cli.modules.hook.HookInput;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;
import dev.alvo.pieria.cli.modules.hook.TraceSpool;
import dev.alvo.pieria.tools.Redaction;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Shared fast path for harness tool-outcome hooks: scrub one event and append it to the local
 * spool. It deliberately never contacts the daemon because it runs inside the agent's tool loop.
 */
abstract class AbstractToolOutcomeHookCommand extends AbstractHookCommand {

  /**
   * Per-field cap applied here rather than daemon-side. The daemon re-applies its configured
   * budget, but the hook cannot read daemon config without a request it must not make, and an
   * uncapped write is what would make this hook slow.
   */
  private static final int CAPTURE_BUDGET_CHARS = 4000;

  @Override
  protected final HookOutcome execute() {
    HookInput input = HookInput.readLenient(System.in);
    if (input.toolName() == null || input.toolName().isBlank()) {
      return new HookOutcome.Skipped("no tool_name in the tool-outcome payload; nothing to record");
    }

    Path repoRoot = Path.of("").toAbsolutePath();
    Path userHome = Path.of(System.getProperty("user.home", "")).toAbsolutePath();
    TraceEventDto event = new TraceEventDto(
      input.toolName(),
      scrub(input.toolInput(), repoRoot, userHome),
      scrub(input.toolResponse(), repoRoot, userHome),
      status(input),
      input.exitCode(),
      scrub(input.error(), repoRoot, userHome),
      null,
      Instant.now());

    new TraceSpool(TraceSpool.defaultRoot()).append(input.sessionId(), event);
    return HookOutcome.ok();
  }

  /** Resolve the harness-specific outcome contract without guessing from output text. */
  protected abstract TraceStatus status(HookInput input);

  /**
   * Truncate first, then redact — that ordering bounds the work by the budget instead of by raw
   * output size. A secret past the budget is discarded rather than scanned and never reaches disk.
   */
  private static String scrub(String text, Path repoRoot, Path userHome) {
    return text == null ? null
      : Redaction.scrub(text, CAPTURE_BUDGET_CHARS, repoRoot, userHome).text();
  }
}
