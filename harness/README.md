# Harness integration

Pieria wires itself into an AI coding harness through two surfaces:

1. **The MCP gateway** — `pieria-gateway`, registered as an MCP server, giving the model the
   `recall`, `remember`, `list`, and `forget` tools.
2. **Lifecycle hooks** — the harness invokes `pieria hook <harness> <event>` at session boundaries.

Both are installed by `pieria harness install [--user] [<harness>]`.

## Hook commands

| Command | When the harness runs it | Effect |
|---|---|---|
| `pieria hook claude-code session-start` | session opens | prints the memory pointer on stdout |
| `pieria hook claude-code pre-compact` | before context compaction | ingests the transcript |
| `pieria hook claude-code stop` | end of a turn | ingests the transcript |
| `pieria hook claude-code session-end` | session ends, including `/clear` | ingests the transcript |
| `pieria hook codex session-start` | session opens | prints the memory pointer |
| `pieria hook codex stop` | end of a turn | ingests the transcript |
| `pieria hook opencode ingest` | compaction | ingests the transcript from **stdin** |
| `pieria hook opencode recall-transform` | system-prompt transform | echoes stdin, then appends recalled context |
| `pieria hook remember <text>` | `/pieria-remember` | pins one memory |

There is deliberately no `hook recall`. Explicit recall is the model's MCP `recall` tool, which
picks its own inference tier per query; the session-open pointer tells it to. A slash command could
only pin the query to the cheapest tier at a fixed limit. To recall from a terminal, use
`pieria profile recall`.

The group is hidden from `pieria --help`; nothing here is meant to be typed by a human.

## Contract

- **Fail-closed.** Every hook exits 0 no matter what — daemon down, model unavailable, malformed
  transcript. A hook must never break or stall a session.
- **stdout is payload.** Only the memory pointer and the remember confirmation go to stdout, because
  a harness injects a hook's stdout into the model's context. Diagnostics go to stderr.
- **Input follows the harness contract.** Claude Code supplies transcript metadata through its
  environment, Codex writes a JSON hook payload containing `transcript_path` and `session_id` to
  stdin, and OpenCode pipes raw transcript or prompt bytes to stdin. Stored command strings contain
  only literals — no shell-expanded variables, so they remain portable to Windows.
- **`PIERIA_DAEMON_URL`** and **`PIERIA_PROFILE`** are honoured, as they are for every `pieria`
  command.

## Adding a harness

1. Add a `HarnessHookSpec` constant in `modules/cli/.../modules/hook/HarnessHookSpec.java`.
2. Add command classes under `modules/cli/.../command/hook/`, extending
   `AbstractIngestHookCommand` or `AbstractPointerHookCommand`.
3. Add an installer under `modules/cli/.../modules/harness/` and register it in `HarnessRegistry`.
4. Add slash-command templates under `harness/<id>/commands/` if the harness supports them.

## Verifying against harness docs

Hook event names and input payloads change across harness releases. The values Pieria relies on are
listed in `HarnessHookSpec` and the harness-specific command adapter; check them against current
harness documentation when a hook stops firing.
