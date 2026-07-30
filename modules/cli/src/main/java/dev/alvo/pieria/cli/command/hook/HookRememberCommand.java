package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HookContext;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;
import dev.alvo.pieria.cli.modules.hook.MemoryPinner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Backs the {@code /pieria-remember} slash command: pins one memory deterministically, without
 * depending on the model choosing to call the MCP tool.
 */
@Command(name = "remember", description = "Pin one memory (backs /pieria-remember).")
public final class HookRememberCommand extends AbstractHookCommand {

  @Parameters(index = "0", paramLabel = "<content>", arity = "0..1",
    description = "Memory text, optionally prefixed with fact:/instruction:/event:/task:.")
  String content;

  @Option(names = "--harness", description = "Harness id for audit attribution (default: unknown).")
  String harness = "unknown";

  @Override
  protected HookOutcome execute() {
    HookOutcome outcome = MemoryPinner.pin(HookContext.create(harness), content);
    return switch (outcome) {
      case HookOutcome.Failed failed -> new HookOutcome.Ok("[pieria/remember] " + failed.reason());
      case HookOutcome.Skipped skipped -> new HookOutcome.Ok(skipped.reason());
      case HookOutcome.Ok ok -> ok;
    };
  }

  @Override
  protected String label() {
    return "pieria/remember";
  }
}
