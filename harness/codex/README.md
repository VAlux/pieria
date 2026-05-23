# Codex CLI Integration

Wires Pieria into the Codex CLI via two surfaces (SPEC §10.4):

1. **MCP stdio shim** — registered via the `[mcp_servers]` section in `config.toml`.
2. **Lifecycle hooks** — `Stop`-hook ingestion (no compaction-specific event in Codex)
   and session-start recall.

> VERIFY all configuration keys and hook surfaces against current Codex CLI docs
> (as of 2026-05). Codex command hooks are a recent addition and their exact
> event model is evolving — confirm the event names and payload contracts before
> production use.

---

## Step 1 — Register the MCP shim

Add an entry to your Codex `config.toml` (typically `~/.codex/config.toml` or
`<project>/.codex/config.toml`):

```toml
[mcp_servers.pieria]
command = "java"
args    = ["-jar", "<PIERIA_JAR>", "--mcp-shim"]

[mcp_servers.pieria.env]
PIERIA_PROFILE    = ""          # leave empty for auto-derivation
PIERIA_DAEMON_URL = "http://127.0.0.1:8077"
```

Replace `<PIERIA_JAR>` with the absolute path to the built jar. Leave
`PIERIA_PROFILE` empty to use automatic profile derivation (git remote / directory
name); set it to an explicit slug to force a specific profile.

The shim exposes `mcp__pieria__recall`, `mcp__pieria__remember`,
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

`session-start.sh` calls `POST /v1/profiles/{name}/recall` and prints the result
to stdout so Codex can surface it. A minimal implementation (mirrors the Claude
Code variant):

```sh
#!/usr/bin/env sh
# session-start.sh for Codex — recall project context at session open.
# VERIFY: confirm SessionStart hook, stdout injection contract, and env vars
# against current Codex CLI docs (as of 2026-05).

_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
_HARNESS_DIR=$(cd "${_SCRIPT_DIR}/.." && pwd)

. "${_HARNESS_DIR}/profile-name.sh"
PROFILE="${PIERIA_RESOLVED_PROFILE:-default}"
DAEMON_URL="${PIERIA_DAEMON_URL:-http://127.0.0.1:8077}"

RESPONSE=$(curl --silent --max-time 10 \
  -H 'Content-Type: application/json' \
  --data '{"query":"What should I know about this project before starting?","limit":10}' \
  "${DAEMON_URL}/v1/profiles/${PROFILE}/recall" 2>/dev/null) || true

if [ -n "$RESPONSE" ]; then
  printf '%s\n' "$RESPONSE"
fi
exit 0
```

> VERIFY: the `SessionStart` event name, whether hook stdout is injected into the
> session context, and the available env vars must be confirmed against current
> Codex CLI docs.

---

## Wrapper scripts

Two small wrapper scripts under `harness/codex/` (not yet created — add them
following the patterns in `harness/claude-code/`) are needed for the hooks:

- `stop.sh` — mirrors `harness/claude-code/stop.sh`, reads `CODEX_TRANSCRIPT_PATH`
  or equivalent env var (verify the name in Codex docs) and calls `ingest.sh`.
- `session-start.sh` — mirrors `harness/claude-code/session-start.sh`.

Both must be fail-closed (exit 0 on any error) and must not contain machine-specific
paths or secrets.

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
