package dev.alvo.pieria.cli.modules.hook;

/**
 * What a hook operation produced. Kept separate from printing so the command layer owns stream
 * discipline: {@link Ok#stdout()} is the only thing that may reach stdout — everything else is a
 * diagnostic for stderr. No variant is an error in the exit-code sense; hooks always exit 0.
 */
public sealed interface HookOutcome {

  /** Succeeded. {@code stdout} is the injection block or confirmation, possibly empty. */
  record Ok(String stdout) implements HookOutcome {
  }

  /** Nothing to do (no transcript, empty input, daemon unhealthy). */
  record Skipped(String reason) implements HookOutcome {
  }

  /** Attempted and failed. The session continues regardless. */
  record Failed(String reason) implements HookOutcome {
  }

  static HookOutcome ok() {
    return new Ok("");
  }
}
