package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import dev.alvo.pieria.cli.modules.hook.HookContext;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;
import dev.alvo.pieria.cli.modules.hook.MemoryPointer;

/**
 * Shared body for session-open hooks: point the agent at the store, don't guess its contents.
 */
abstract class AbstractPointerHookCommand extends AbstractHookCommand {

  protected abstract HarnessHookSpec spec();

  @Override
  protected HookOutcome execute() {
    return MemoryPointer.render(HookContext.create(spec().id()));
  }
}
