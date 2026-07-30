package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.ContextRecaller;
import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import dev.alvo.pieria.cli.modules.hook.HookContext;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;

/** Shared body for session-open primers: recall the project context block onto stdout. */
abstract class AbstractPrimerHookCommand extends AbstractHookCommand {

  protected abstract HarnessHookSpec spec();

  @Override
  protected HookOutcome execute() {
    HookContext ctx = HookContext.create(spec().id());
    return ContextRecaller.recall(ctx, spec().primerQuery(), spec().primerLimit());
  }
}
