# OpenCode Integration

Wires Pieria into OpenCode via two surfaces (SPEC §10.4):

1. **MCP stdio gateway** — registered via the `mcp` key in `opencode.json`.
2. **Session lifecycle hooks** — compaction-time ingestion and session-bootstrap recall
   using OpenCode's experimental plugin surfaces.

> VERIFY all configuration keys and hook surfaces against current OpenCode docs
> (as of 2026-05). The `experimental.*` namespaces described below are
> community-documented and must be confirmed against the OpenCode repository
> changelog before use in production.

---

## Step 1 — Register the MCP gateway

Add a `mcp` entry to your `opencode.json` (project root or `~/.config/opencode/opencode.json`):

```json
{
  "mcp": {
    "pieria": {
      "type": "local",
      "command": ["java", "-jar", "<PIERIA_GATEWAY_JAR>"],
      "env": {
        "PIERIA_PROFILE": "",
        "PIERIA_DAEMON_URL": "http://127.0.0.1:8077"
      }
    }
  }
}
```

Replace `<PIERIA_GATEWAY_JAR>` with the absolute path to `gateway/build/libs/pieria-gateway.jar`.
Leave
`PIERIA_PROFILE` empty to use automatic profile derivation (git remote / directory
name); set it to an explicit slug to force a specific profile.

The gateway exposes `mcp__pieria__recall`, `mcp__pieria__remember`,
`mcp__pieria__list`, and `mcp__pieria__forget` as model-facing tools.

> VERIFY: confirm the `mcp.*.type` value (`"local"` vs `"stdio"` vs `"process"`)
> and the `command` array format against current OpenCode docs.

---

## Step 2 — Compaction-time ingestion

Use the `experimental.session.compacting` plugin hook to ingest the transcript
before OpenCode compacts the context window:

```json
{
  "experimental": {
    "session": {
      "compacting": {
        "plugin": "sh <PIERIA_HARNESS_DIR>/ingest.sh"
      }
    }
  }
}
```

Replace `<PIERIA_HARNESS_DIR>` with the absolute path to the `harness/` directory.

The hook calls `harness/ingest.sh` which reads a transcript from stdin or a file
argument, derives the profile, and POSTs to `/v1/profiles/{name}/ingest`. The hook
is fail-closed: daemon errors are logged to stderr and the hook exits 0.

> VERIFY: the `experimental.session.compacting` key name, the plugin invocation
> model (stdin/file/arg passing), and the transcript format the hook receives must
> be confirmed against current OpenCode docs. This surface is experimental.

---

## Step 3 — Session-bootstrap recall (surrogate for SessionStart)

**Known limitation: OpenCode has no `SessionStart` hook as of 2026-05 (issue #14808).**
The community workaround is `experimental.chat.system.transform`, which lets a
plugin augment the system prompt at the start of each chat turn. Use it to inject
prior memories into the system prompt:

```json
{
  "experimental": {
    "chat": {
      "system": {
        "transform": "sh <PIERIA_HARNESS_DIR>/opencode/recall-transform.sh"
      }
    }
  }
}
```

`recall-transform.sh` should call `POST /v1/profiles/{name}/recall` and append
the returned answer to the system prompt it receives on stdin. A minimal example:

```sh
#!/usr/bin/env sh
# recall-transform.sh — augments the OpenCode system prompt with prior memories.
# VERIFY: confirm stdin/stdout contract for system.transform plugins against
# current OpenCode docs (as of 2026-05).

_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
_HARNESS_DIR=$(cd "${_SCRIPT_DIR}/.." && pwd)

. "${_HARNESS_DIR}/profile-name.sh"
PROFILE="${PIERIA_RESOLVED_PROFILE:-default}"
DAEMON_URL="${PIERIA_DAEMON_URL:-http://127.0.0.1:8077}"

# Pass through the original system prompt first.
cat

# Append recalled context (fail silently if daemon is down).
RESPONSE=$(curl --silent --max-time 8 \
  -H 'Content-Type: application/json' \
  --data '{"query":"What should I know about this project?","limit":10}' \
  "${DAEMON_URL}/v1/profiles/${PROFILE}/recall" 2>/dev/null) || true

if [ -n "$RESPONSE" ]; then
  printf '\n\n---\nPrior project context (Pieria):\n%s\n' "$RESPONSE"
fi
```

> VERIFY: the `experimental.chat.system.transform` key, the stdin/stdout contract,
> and whether it fires per-session or per-turn must be confirmed against current
> OpenCode docs. This is an experimental surface that may change.

---

## Profile mapping

Same contract as all other harnesses — see `harness/profile-name.sh`:

1. `$PIERIA_PROFILE` env var (highest priority, set it in the `env` block)
2. Last segment of `git config --get remote.origin.url`, minus `.git`
3. `basename "$PWD"`

---

## Sharing memory across harnesses

Set `PIERIA_PROFILE=<slug>` in the `env` block for the MCP server entry and in
the hook env to force a specific profile. Multiple harnesses pointing at the same
slug share the same memory store through the common daemon.

---

## Version verification summary

All the following must be verified against current OpenCode documentation before
deploying (as of 2026-05):

| Surface | Key | Status |
|---------|-----|--------|
| MCP server registration | `mcp.*.type` / `command` format | Stable — verify key names |
| Compaction hook | `experimental.session.compacting.plugin` | Experimental |
| Session-bootstrap recall | `experimental.chat.system.transform` | Experimental; no SessionStart event yet (issue #14808) |
