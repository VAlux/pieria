package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.cli.modules.hook.HookInput;
import picocli.CommandLine.Command;

/**
 * Claude Code {@code PostToolUse}/{@code PostToolUseFailure}: record one tool call.
 *
 * <p>This runs after <em>every</em> tool call, inside the agent's loop, so it does exactly two
 * things — scrub and append a line — and never contacts the daemon. The turn-end hooks ship the
 * batch.
 */
@Command(name = "post-tool-use", description = "Claude Code tool-outcome hook.")
public final class CcPostToolUseCommand extends AbstractToolOutcomeHookCommand {

  /**
   * Claude Code splits successful and failed calls into distinct hook events. The event name is
   * therefore authoritative; a numeric exit code is only a compatibility fallback for older
   * harness payloads that did not identify the event.
   */
  protected TraceStatus status(HookInput input) {
    if ("PostToolUse".equals(input.hookEventName())) {
      return TraceStatus.SUCCESS;
    }
    if ("PostToolUseFailure".equals(input.hookEventName())) {
      return TraceStatus.FAILURE;
    }
    if (input.exitCode() == null) {
      return TraceStatus.UNKNOWN;
    }
    return input.exitCode() == 0 ? TraceStatus.SUCCESS : TraceStatus.FAILURE;
  }

  @Override
  protected String label() {
    return "pieria/claude-code-post-tool-use";
  }
}
