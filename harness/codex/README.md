# Codex CLI Integration

Wires Pieria into the Codex CLI via two surfaces:

1. **MCP stdio gateway** — registered via the `[mcp_servers]` section in `config.toml`.
2. **Lifecycle hooks** — `Stop`-hook ingestion (no compaction-specific event in Codex)
   and session-start recall.

> VERIFY all configuration keys and hook surfaces against current Codex CLI docs
> (as of 2026-05). Codex command hooks are a recent addition and their exact
> event model is evolving — confirm the event names and payload contracts before
> production use.

---

## Step 1 — Register the MCP gateway

Add an entry to your Codex `config.toml` (typically `~/.codex/config.toml` or
`<project>/.codex/config.toml`):

```toml
[mcp_servers.pieria]
command = "java"
args    = ["-jar", "<PIERIA_GATEWAY_JAR>"]

[mcp_servers.pieria.env]
PIERIA_PROFILE    = ""          # leave empty for auto-derivation
PIERIA_DAEMON_URL = "http://127.0.0.1:8077"
```

Replace `<PIERIA_GATEWAY_JAR>` with the absolute path to `gateway/build/libs/pieria-gateway.jar`.
Leave
`PIERIA_PROFILE` empty to use automatic profile derivation (git remote / directory
name); set it to an explicit slug to force a specific profile.

The gateway exposes `mcp__pieria__recall`, `mcp__pieria__remember`,
`mcp__pieria__list`, and `mcp__pieria__forget` as model-facing tools.

> VERIFY: confirm the `[mcp_servers.*]` table name, `command`/`args`/`env` keys,
> and the TOML file location against current Codex CLI docs.

---

## Step 2 — Ingestion via Stop hook

Codex CLI has no compaction-specific lifecycle event as of 2026-05. Use the
`Stop` hook (session-end event) to ingest the final transcript:

```toml
[[hooks]]
event   = "Stop"
command = "sh <PIERIA_HARNESS_DIR>/codex/stop.sh"
```

Replace `<PIERIA_HARNESS_DIR>` with the absolute path to the `harness/` directory.

The `stop.sh` wrapper reads the transcript path from the environment and calls
`harness/ingest.sh`. It is fail-closed: errors are logged to stderr and the hook
exits 0 so Codex session shutdown is never blocked.

> VERIFY: the `Stop` event name, the `[[hooks]]` table structure, and the
> environment variables available inside hook commands (transcript path, session ID)
> must be confirmed against current Codex CLI docs (as of 2026-05).
>
> IMPORTANT: Codex command hooks are command-only. Prompt and agent handlers are
> skipped; hooks fire only on tool/command events. Verify this constraint applies
> to the Stop event before relying on it for transcript capture.

---

## Step 3 — Session-start recall

Codex CLI has a `SessionStart` hook that can inject context before the first
agent turn:

```toml
[[hooks]]
event   = "SessionStart"
command = "sh <PIERIA_HARNESS_DIR>/codex/session-start.sh"
```

`session-start.sh` delegates to the shared `harness/recall.sh`, which uses the
daemon's **fast recall** path (`fast:true`): deterministic query analysis and no
synthesis, returning the top memories as a ready-to-inject text block in ~1-3s
(instead of the tens of seconds a full synthesized recall takes), and excluding
auto-indexed code-graph memories. It prints that block to stdout for Codex to
surface before the first turn. Honors `PIERIA_RECALL_QUERY`, `PIERIA_RECALL_LIMIT`
(default 10), and `PIERIA_RECALL_TIMEOUT` (default 8).

**No per-prompt recall on Codex.** Codex command hooks are command-only — they fire
on tool/command events, not on prompt submission — so there is no equivalent to
Claude Code's `UserPromptSubmit` auto-recall. The session-open primer is the recall
surface Codex supports.

> VERIFY: the `SessionStart` event name, whether hook stdout is injected into the
> session context, and the available env vars must be confirmed against current
> Codex CLI docs.

---

## Wrapper scripts

Two small wrapper scripts under `harness/codex/` back the hooks (both fail-closed —
exit 0 on any error — and contain no machine-specific paths or secrets):

- `stop.sh` — mirrors `harness/claude-code/stop.sh`; reads the transcript path env
  var (verify the name in Codex docs) and calls the shared `harness/ingest.sh`.
- `session-start.sh` — calls the shared `harness/recall.sh` (fast recall) and prints
  the result for Codex to surface.

`pieria harness install codex` extracts these (plus the shared `profile-name.sh`,
`ingest.sh`, and `recall.sh`) and wires the `[[hooks]]` entries automatically.

---

## Profile mapping

Same contract as all other harnesses — see `harness/profile-name.sh`:

1. `$PIERIA_PROFILE` env var (highest priority, set it in `[mcp_servers.pieria.env]`)
2. Last segment of `git config --get remote.origin.url`, minus `.git`
3. `basename "$PWD"`

---

## Sharing memory across harnesses

Set `PIERIA_PROFILE = "<slug>"` in `[mcp_servers.pieria.env]` and in the hook
shell env. Multiple harnesses pointing at the same slug share the same memory
store through the common daemon.

---

## Version verification summary

All the following must be verified against current Codex CLI documentation before
deploying (as of 2026-05):

| Surface | Key | Status |
|---------|-----|--------|
| MCP server registration | `[mcp_servers.*]` in `config.toml` | Recent — verify key names |
| Stop hook | `[[hooks]] event = "Stop"` | Recent; command-only (no prompt/agent handlers) |
| SessionStart hook | `[[hooks]] event = "SessionStart"` | Recent — verify availability |
| Transcript env var | Name of the env var exposing the transcript path | Must be verified |
