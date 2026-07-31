package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;

import java.util.concurrent.Callable;

/**
 * Base for every {@code pieria hook} command. Enforces the fail-closed contract: whatever happens,
 * the process exits 0, because a non-zero exit breaks the harness session this hook is embedded in.
 *
 * <p>Stream discipline mirrors the shell scripts this replaces — only {@link HookOutcome.Ok#stdout()}
 * reaches stdout; skips and failures are stderr diagnostics. This is why hook commands do not extend
 * {@code AbstractProfileCommand}, whose 1/3/4 exit codes would be fatal here.
 */
abstract class AbstractHookCommand implements Callable<Integer> {

  protected final Logger log = new Logger();

  /** Do the work. Implementations should return an outcome rather than throwing. */
  protected abstract HookOutcome execute();

  /** A short tag for diagnostics, e.g. {@code pieria/claude-code-stop}. */
  protected abstract String label();

  @Override
  public final Integer call() {
    try {
      HookOutcome outcome = execute();
      switch (outcome) {
        case HookOutcome.Ok ok -> {
          if (!ok.stdout().isEmpty()) {
            System.out.print(ok.stdout().endsWith("\n") ? ok.stdout() : ok.stdout() + "\n");
          }
        }
        case HookOutcome.Skipped skipped -> log.error("[{}] {}", label(), skipped.reason());
        case HookOutcome.Failed failed -> log.error("[{}] {}", label(), failed.reason());
      }
    } catch (Throwable t) {
      log.error("[{}] unexpected error: {}", label(), String.valueOf(t.getMessage()));
    }
    return 0;
  }
}
