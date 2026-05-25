package dev.alvo.pieria.cli.harness;

/** Where a harness's Pieria wiring is written. */
public enum Scope {
  /** Current project/repo directory (e.g. {@code ./.claude/}, {@code ./.mcp.json}). */
  PROJECT,
  /** User home (e.g. {@code ~/.claude/}, {@code ~/.codex/}). */
  USER
}
