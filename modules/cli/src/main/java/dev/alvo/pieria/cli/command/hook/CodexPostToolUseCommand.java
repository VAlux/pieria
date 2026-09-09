package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.cli.modules.hook.HookInput;
import picocli.CommandLine.Command;

/** Codex {@code PostToolUse}: record one completed tool call in the local trace spool. */
@Command(name = "post-tool-use", description = "Codex tool-outcome hook.")
public final class CodexPostToolUseCommand extends AbstractToolOutcomeHookCommand {

  /**
   * Codex only emits PostToolUse for results it regards as successful, except that Bash's stable
   * hook response deliberately contains output alone and omits the process exit code. A non-zero
   * shell command is therefore indistinguishable from a successful one. Preserve that uncertainty
   * instead of inventing success; accept an exit code if a future Codex payload exposes one.
   */
  @Override
  protected TraceStatus status(HookInput input) {
    if ("PostToolUseFailure".equals(input.hookEventName())) {
      return TraceStatus.FAILURE;
    }
    if (input.exitCode() != null) {
      return input.exitCode() == 0 ? TraceStatus.SUCCESS : TraceStatus.FAILURE;
    }
    if (!"PostToolUse".equals(input.hookEventName())) {
      return TraceStatus.UNKNOWN;
    }
    return "Bash".equals(input.toolName()) ? TraceStatus.UNKNOWN : TraceStatus.SUCCESS;
  }

  @Override
  protected String label() {
    return "pieria/codex-post-tool-use";
  }
}
