package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.ContextRecaller;
import dev.alvo.pieria.cli.modules.hook.HookContext;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Backs the {@code /pieria-recall} slash command: prints a context block for an explicit query.
 * Harness-agnostic, so the caller declares which harness it is for audit attribution.
 */
@Command(name = "recall", description = "Recall context for a query (backs /pieria-recall).")
public final class HookRecallCommand extends AbstractHookCommand {

  @Parameters(index = "0", paramLabel = "<query>", arity = "0..1", description = "Recall query.")
  String query;

  @Option(names = "--limit", description = "Maximum memories to inject (default: 10).")
  int limit = 10;

  @Option(names = "--harness", description = "Harness id for audit attribution (default: unknown).")
  String harness = "unknown";

  @Override
  protected HookOutcome execute() {
    return ContextRecaller.recall(HookContext.create(harness), query, limit);
  }

  @Override
  protected String label() {
    return "pieria/recall";
  }
}
