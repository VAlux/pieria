package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.ContextRecaller;
import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import dev.alvo.pieria.cli.modules.hook.HookContext;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * OpenCode's {@code experimental.chat.system.transform} filter. OpenCode has no SessionStart event,
 * so the system prompt is augmented instead: pass the original through unchanged, then append the
 * recalled block. The passthrough happens first and unconditionally — a recall failure must never
 * swallow the prompt.
 */
@Command(name = "recall-transform",
  description = "OpenCode system-prompt transform; passes stdin through and appends recalled context.")
public final class OpenCodeRecallTransformCommand extends AbstractHookCommand {

  @Override
  protected HookOutcome execute() {
    String original;
    try {
      original = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return new HookOutcome.Failed("could not read the system prompt from stdin: " + e.getMessage());
    }
    log.print(original);

    HookContext ctx = HookContext.create(HarnessHookSpec.OPENCODE.id());
    HookOutcome recalled =
      ContextRecaller.recall(ctx, HarnessHookSpec.OPENCODE.primerQuery(), HarnessHookSpec.OPENCODE.primerLimit());
    if (recalled instanceof HookOutcome.Ok ok && !ok.stdout().isBlank()) {
      return new HookOutcome.Ok("\n\n---\nPrior project context (Pieria):\n" + ok.stdout());
    }
    return HookOutcome.ok();
  }

  @Override
  protected String label() {
    return "pieria/opencode-recall-transform";
  }
}
