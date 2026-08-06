package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import dev.alvo.pieria.cli.modules.hook.HookContext;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;
import dev.alvo.pieria.cli.modules.hook.MemoryPointer;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * OpenCode's {@code experimental.chat.system.transform} filter. OpenCode has no SessionStart event,
 * so the system prompt is augmented instead: pass the original through unchanged, then append the
 * memory pointer. The passthrough happens first and unconditionally — a pointer failure must never
 * swallow the prompt.
 *
 * <p>The command keeps its {@code recall-transform} name: installed OpenCode configurations name it
 * directly, and renaming it would break them on upgrade.
 */
@Command(name = "recall-transform",
  description = "OpenCode system-prompt transform; passes stdin through and appends the memory pointer.")
public final class OpenCodeRecallTransformCommand extends AbstractHookCommand {

  @Override
  protected HookOutcome execute() {
    String original;
    try {
      original = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return new HookOutcome.Failed("could not read the system prompt from stdin: " + e.getMessage());
    }
    System.out.print(original);

    HookContext ctx = HookContext.create(HarnessHookSpec.OPENCODE.id());
    HookOutcome pointer = MemoryPointer.render(ctx);
    if (pointer instanceof HookOutcome.Ok(String stdout) && !stdout.isBlank()) {
      return new HookOutcome.Ok("\n\n---\n" + stdout);
    }

    return HookOutcome.ok();
  }

  @Override
  protected String label() {
    return "pieria/opencode-recall-transform";
  }
}
