# Pieria Harness Integration

This directory contains the glue assets that connect AI agent harnesses
(Claude Code, OpenCode, Codex, and any MCP-capable tool) to the Pieria memory
daemon.

---

## Two integration surfaces

Every harness uses the same two surfaces regardless of vendor:

### 1. Model-driven MCP tools

The model calls `recall`, `remember`, `list`, or `forget` mid-task. These tools
are served by the **MCP stdio gateway** — a thin process that speaks MCP over stdio
toward the harness and forwards calls to the daemon over localhost HTTP.

Launch command (all harnesses):

```sh
java -jar <PIERIA_GATEWAY_JAR>
```

The gateway jar is built by `./gradlew :gateway:bootJar` and is separate from the
daemon jar built by `./gradlew :daemon:bootJar`. The daemon remains the long-lived
process that owns storage; the gateway remains the harness-spawned stdio process.

MCP tools exposed:

| MCP tool name | Daemon endpoint | Model-facing? |
|---------------|----------------|---------------|
| `mcp__pieria__recall` | `POST /v1/profiles/{name}/recall` | Yes |
| `mcp__pieria__remember` | `POST /v1/profiles/{name}/memories` | Yes |
| `mcp__pieria__list` | `GET /v1/profiles/{name}/memories` | Yes |
| `mcp__pieria__forget` | `DELETE /v1/profiles/{name}/memories/{id}` | Yes |

`ingest` is intentionally absent from the model-facing surface — bulk ingestion
is the harness's job, not the model's.

### 2. Harness-driven ingestion hooks

At compaction (or session end) the harness sends the conversation transcript to
`POST /v1/profiles/{name}/ingest`. This is the write path that extracts and
persists memories from raw conversation text. The harness calls the hook; the
model is not involved.

The shared ingestion client is `harness/ingest.sh`. Each harness-specific
subdirectory contains wrapper scripts that adapt the hook event to call it.

**Recall timing**:
- On demand: the model calls `recall` mid-task via the MCP tool.
- At session start: the `SessionStart` hook calls `/recall` and injects prior
  context so Claude starts primed even without an explicit model recall.
- At compaction / turn end / session end: the `PreCompact` / `Stop` / `SessionEnd`
  hooks call `/ingest` to capture memories before context is discarded (`SessionEnd`
  fires on `/clear`, quit, and logout).

---

## Shared profile-mapping contract

All harnesses and the gateway use the same profile-name derivation.
The canonical logic is in `ProfileResolver.java`; the shell equivalent is
`harness/profile-name.sh`. Source or invoke that script in any hook to get
the correct slug.

Resolution order (highest to lowest):
1. `$PIERIA_PROFILE` env var — explicit override, highest priority.
2. Last path segment of `git config --get remote.origin.url`, minus `.git`.
3. `basename "$PWD"` — working-directory name.

The raw name is normalized to a lower-case `[a-z0-9-]` slug (non-alnum runs
replaced with a single hyphen, leading/trailing hyphens trimmed, empty → `"default"`).

---

## Localhost-only security assumption

In local mode the daemon binds `127.0.0.1` only and never accepts connections from
remote hosts. The MCP gateway connects to the daemon over localhost HTTP. No
authentication is required in this topology — the security model relies on the
OS-level localhost isolation. **Never expose the daemon port externally
in local mode.** Server mode adds authentication and multi-tenant
isolation.

---

## Sharing memory across multiple harnesses

Because all harnesses talk to the same daemon, they share memory automatically
when they resolve to the same profile name.

For automatic sharing (same repo/directory), do nothing: the profile is derived
from the git remote or `$PWD`, so Claude Code, OpenCode, and Codex all arrive at
the same slug when run from the same project.

For explicit cross-tool sharing, set `PIERIA_PROFILE=<slug>` in every harness's
MCP server env block and hook environment. Any harness pointing at the same slug
reads and writes the same memory store.

---

## Directory layout

```
harness/
  profile-name.sh          # POSIX shell profile resolver (source in hooks)
  ingest.sh                # shared ingestion hook client
  recall.sh                # shared fast-recall client (hooks + /pieria-recall)
  remember.sh              # shared explicit-memory client (/pieria-remember)
  claude-code/
    .mcp.json              # MCP server registration fragment
    settings-hooks-snippet.json  # settings.json hook config (paste into Claude Code)
    session-start.sh       # SessionStart hook
    pre-compact.sh         # PreCompact hook
    stop.sh                # Stop hook
    session-end.sh         # SessionEnd hook (/clear, quit, logout)
    commands/              # /pieria-remember, /pieria-recall slash commands
    README.md              # Claude Code setup guide
  opencode/
    recall-transform.sh    # experimental.chat.system.transform recall hook
    commands/              # /pieria-remember, /pieria-recall slash commands
    README.md              # OpenCode setup guide
  codex/
    stop.sh                # Stop hook
    session-start.sh       # SessionStart hook
    commands/              # /pieria-remember, /pieria-recall slash commands (model-mediated)
    README.md              # Codex CLI setup guide
  README.md                # this file
```

---

## User-triggered slash commands

Alongside the automatic lifecycle hooks, `pieria harness install <harness>` also installs two
**user-triggered** slash commands:

- **`/pieria-remember [type:] <content>`** — deterministically pin a memory (`POST /memories`),
  without depending on the model choosing to call the MCP `remember` tool. `type` is one of
  `fact` (default), `instruction`, `event`, `task`.
- **`/pieria-recall <query>`** — recall relevant prior context on demand and inject the answer.

On Claude Code and OpenCode these run the shared `remember.sh`/`recall.sh` clients directly
(deterministic). Codex prompts cannot execute shell, so there the commands are model-mediated —
they instruct the model to call the `mcp__pieria__remember` / `mcp__pieria__recall` tools.

---

## Harness support matrix

| Harness | MCP tools | Ingestion hook | Session-start recall | Slash commands | Notes |
|---------|-----------|----------------|----------------------|----------------|-------|
| Claude Code | `claude mcp add` / `.mcp.json` | `PreCompact` + `Stop` + `SessionEnd` hooks | `SessionStart` hook | `/pieria-remember`, `/pieria-recall` (shell, deterministic) | First-class; `pieria harness install claude-code` |
| OpenCode | `mcp` key in `opencode.json` | `experimental.session.compacting` | `experimental.chat.system.transform` | `/pieria-remember`, `/pieria-recall` (shell, deterministic) | `pieria harness install opencode`; experimental surfaces — verify |
| Codex CLI | `[mcp_servers]` in `config.toml` | `Stop` hook | `SessionStart` hook | `/pieria-remember`, `/pieria-recall` (model-mediated) | `pieria harness install codex`; hooks/prompts recent — verify |
| Custom | MCP client or direct REST | Call `/ingest` at compaction | Call `/recall` on bootstrap | Call `/memories` + `/recall` | Use `harness/ingest.sh` as a reference |

---

## Version verification

Hook event names, environment variables, and experimental API surfaces change
across harness releases. Every harness-specific README marks version-sensitive
items with a "VERIFY against current \<harness\> docs (as of 2026-05)" note.
Consult the current docs for your installed harness version before deploying hooks
to a team.

See also: `docs/HARNESS.md` for the full integration summary.
