# Harness Integration

Summary of the harness-facing glue assets.
Cross-references: `harness/README.md`.

---

## Integration architecture

Harnesses connect to Pieria via two surfaces:

```
  AI harness ──► MCP stdio gateway ──► Daemon REST (/v1/profiles/{name}/...)
                 (model tools)      (http://127.0.0.1:8077)
  AI harness ──► lifecycle hook ──► POST /ingest  (harness-driven, at compaction)
```

The gateway and hooks share a single profile-name derivation algorithm so every
component writes to and reads from the same profile.

---

## Delivered assets (`harness/`)

| File | Purpose |
|------|---------|
| `harness/profile-name.sh` | POSIX shell profile resolver; mirrors `ProfileResolver.java` |
| `harness/ingest.sh` | Shared ingestion hook client; POSTs transcript to `/ingest`; fail-closed |
| `harness/claude-code/.mcp.json` | MCP server registration fragment for Claude Code |
| `harness/claude-code/settings-hooks-snippet.json` | `settings.json` hook config snippet |
| `harness/claude-code/session-start.sh` | `SessionStart` hook — calls `/recall` to prime context |
| `harness/claude-code/pre-compact.sh` | `PreCompact` hook — calls `/ingest` before compaction |
| `harness/claude-code/stop.sh` | `Stop` hook — calls `/ingest` at session end |
| `harness/claude-code/README.md` | Claude Code setup guide (install steps, profile mapping) |
| `harness/opencode/README.md` | OpenCode setup guide (MCP, compaction hook, system transform) |
| `harness/codex/README.md` | Codex CLI setup guide (MCP, stop hook, session-start hook) |
| `harness/README.md` | Top-level overview: surfaces, profile contract, security, sharing |

---

## Profile-name resolution

All components agree on the same precedence (implemented in `ProfileResolver.java`
and mirrored in `harness/profile-name.sh`):

1. `$PIERIA_PROFILE` env var — explicit override.
2. Last segment of `git config --get remote.origin.url`, minus `.git`.
3. `basename "$PWD"` — working-directory name.

Normalization: lower-case, non-`[a-z0-9-]` runs → single hyphen, repeated hyphens
collapsed, leading/trailing hyphens trimmed, empty → `"default"`.

---

## Daemon REST surface (for hook authors)

Base URL: `http://${PIERIA_DAEMON_URL:-127.0.0.1:8077}` (configurable via
`PIERIA_DAEMON_URL` env var or `pieria.daemon.host` / `pieria.daemon.port`
properties).

| Endpoint | Method | Hook usage |
|----------|--------|-----------|
| `/pieria-health` | GET | Liveness check before hook invocations |
| `/v1/profiles/{name}/ingest` | POST | Ingestion hooks (PreCompact, Stop) |
| `/v1/profiles/{name}/recall` | POST | SessionStart context priming |

Ingest payload shape:
```json
{
  "sessionId": "<id>",
  "messages": [
    { "role": "user",      "content": "..." },
    { "role": "assistant", "content": "..." }
  ]
}
```

Recall payload shape:
```json
{ "query": "...", "limit": 10 }
```

Recall response shape:
```json
{ "answer": "...", "memories": [...] }
```

---

## Fail-closed contract

Every hook script and the `ingest.sh` client must exit 0 on any error (curl
failure, non-2xx response, daemon unreachable). Daemon availability must never
block or break the harness session. Errors are logged to stderr only.

---

## Security assumption (local mode)

The daemon binds `127.0.0.1` only in local mode. No authentication is
required. Never expose the daemon port on a network interface in local mode.
Server mode adds authentication and multi-tenant Row-Level Security.

---

## Version-sensitive items

The following harness surfaces must be verified against current harness
documentation before deploying to a team. They are marked with
"VERIFY against current \<harness\> docs (as of 2026-05)" notes in each README.

### Claude Code
- Hook event names: `SessionStart`, `PreCompact`, `Stop`
- Env vars available in hook commands: `CLAUDE_TRANSCRIPT_PATH`, `CLAUDE_SESSION_ID`
- `claude mcp add` CLI syntax and `.mcp.json` format

### OpenCode
- `mcp.*.type` value and `command` array format in `opencode.json`
- `experimental.session.compacting.plugin` key name and stdin/file contract
- `experimental.chat.system.transform` key name and stdin/stdout contract
- OpenCode has **no `SessionStart` hook as of 2026-05** (issue #14808);
  `experimental.chat.system.transform` is the community surrogate

### Codex CLI
- `[mcp_servers.*]` table name and key format in `config.toml`
- `[[hooks]]` event names: `Stop`, `SessionStart`
- Codex command hooks are **command-only** (prompt/agent handlers skipped) —
  verify this applies to session lifecycle events
- Env var exposing the transcript path inside hook commands (name unconfirmed)

---

## Future work: Marketplace plugin

Bundle the Claude Code gateway registration and all three hooks into a single
installable plugin via a marketplace manifest (`claude plugin add`). The manual
install steps in `harness/claude-code/README.md` are the interim path.

OpenCode ships as an npm plugin referenced in `opencode.json`; Codex is configured
via `config.toml`. An installer will register and start the daemon as
an OS service (launchd / systemd / Windows service).
