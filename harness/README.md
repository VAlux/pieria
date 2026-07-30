# Harness integration

Pieria wires itself into an AI coding harness through two surfaces:

1. **The MCP gateway** — `pieria-gateway`, registered as an MCP server, giving the model the
   `recall`, `remember`, `list`, and `forget` tools.
2. **Lifecycle hooks** — the harness invokes `pieria hook <harness> <event>` at session boundaries.

Both are installed by `pieria harness install [--user] [<harness>]`.

## Hook commands

| Command | When the harness runs it | Effect |
|---|---|---|
| `pieria hook claude-code session-start` | session opens | prints a recalled context block on stdout |
| `pieria hook claude-code pre-compact` | before context compaction | ingests the transcript |
| `pieria hook claude-code stop` | end of a turn | ingests the transcript |
| `pieria hook claude-code session-end` | session ends, including `/clear` | ingests the transcript |
| `pieria hook codex session-start` | session opens | prints a recalled context block |
| `pieria hook codex stop` | session ends | ingests the transcript |
| `pieria hook opencode ingest` | compaction | ingests the transcript from **stdin** |
| `pieria hook opencode recall-transform` | system-prompt transform | echoes stdin, then appends recalled context |
| `pieria hook recall <query>` | `/pieria-recall` | prints a recalled context block |
| `pieria hook remember <text>` | `/pieria-remember` | pins one memory |

The group is hidden from `pieria --help`; nothing here is meant to be typed by a human.

## Contract

- **Fail-closed.** Every hook exits 0 no matter what — daemon down, model unavailable, malformed
  transcript. A hook must never break or stall a session.
- **stdout is payload.** Only the recall block and the remember confirmation go to stdout, because
  a harness injects a hook's stdout into the model's context. Diagnostics go to stderr.
- **Input comes from the environment.** The harness passes the transcript path in its own variable
  (`CLAUDE_TRANSCRIPT_PATH`, `CODEX_TRANSCRIPT_PATH`, …) and the CLI reads it directly. The command
  string stored in harness config contains only literals — no `$VAR`, because expanding one needs a
  shell, which Windows does not provide.
- **`PIERIA_DAEMON_URL`** and **`PIERIA_PROFILE`** are honoured, as they are for every `pieria`
  command.

## Adding a harness

1. Add a `HarnessHookSpec` constant in `modules/cli/.../modules/hook/HarnessHookSpec.java`.
2. Add command classes under `modules/cli/.../command/hook/`, extending
   `AbstractIngestHookCommand` or `AbstractPrimerHookCommand`.
3. Add an installer under `modules/cli/.../modules/harness/` and register it in `HarnessRegistry`.
4. Add slash-command templates under `harness/<id>/commands/` if the harness supports them.

## Verifying against harness docs

Hook event names and environment variables change across harness releases. The values Pieria relies
on are listed in `HarnessHookSpec`; check them against current harness documentation when a hook
stops firing.
