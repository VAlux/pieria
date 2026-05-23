# Claude Code Integration

Wires Pieria into Claude Code via two surfaces (SPEC §10, §10.4):

1. **MCP stdio shim** — registers `mcp__pieria__recall`, `mcp__pieria__remember`,
   `mcp__pieria__list`, and `mcp__pieria__forget` as model-facing tools.
2. **Lifecycle hooks** — `SessionStart` primes context from prior memories;
   `PreCompact` and `Stop` ingest the transcript so memories survive compaction.

## Prerequisites

- Pieria daemon is running and reachable at `http://127.0.0.1:8077` (default).
  Start it with `./gradlew bootRun` or the installed OS service (Phase 5).
  Verify: `curl http://127.0.0.1:8077/healthz`
- A built jar is available at a known path (e.g. `build/libs/pieria-0.0.1-SNAPSHOT.jar`).
  Build: `./gradlew build`
- Java 25 is on `$PATH` (required to launch the shim).

## Step 1 — Register the MCP shim

**Option A: via `claude mcp add` (recommended)**

```sh
claude mcp add pieria \
  -- java -jar <PIERIA_JAR> --mcp-shim
```

Replace `<PIERIA_JAR>` with the absolute path to the built jar.

To set an explicit profile (instead of the auto-derived one):

```sh
claude mcp add pieria \
  -e PIERIA_PROFILE=my-project \
  -- java -jar <PIERIA_JAR> --mcp-shim
```

**Option B: copy `.mcp.json` into your project root**

Copy `harness/claude-code/.mcp.json` to your project root (or
`~/.claude/.mcp.json` for user-level registration) and replace `<PIERIA_JAR>`
with the absolute path to the jar.

After registration, Claude Code surfaces the tools as `mcp__pieria__recall`, etc.
The shim derives the profile from `$PIERIA_PROFILE` > git remote > directory name
(same logic as `harness/profile-name.sh` and `ProfileResolver.java`).

## Step 2 — Install lifecycle hooks

Add the hook commands to your Claude Code `settings.json`.

**Project-level** (`.claude/settings.json` in your project root — recommended):

```json
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "sh <PIERIA_HARNESS_DIR>/claude-code/session-start.sh"
          }
        ]
      }
    ],
    "PreCompact": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "sh <PIERIA_HARNESS_DIR>/claude-code/pre-compact.sh"
          }
        ]
      }
    ],
    "Stop": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "sh <PIERIA_HARNESS_DIR>/claude-code/stop.sh"
          }
        ]
      }
    ]
  }
}
```

Replace `<PIERIA_HARNESS_DIR>` with the absolute path to the `harness/` directory.
A ready-to-paste snippet (with `_comment` keys showing placement) is in
`harness/claude-code/settings-hooks-snippet.json`.

## Hook behaviour summary

| Hook | Trigger | Pieria action |
|------|---------|---------------|
| `SessionStart` | Session opens | POST `/recall` → inject prior context |
| `PreCompact` | Before context compaction | POST `/ingest` with current transcript |
| `Stop` | Session ends | POST `/ingest` with final transcript |

All hooks are fail-closed: daemon unavailability is logged to stderr and the hook
exits 0 so Claude Code is never blocked.

## Profile mapping

The profile name is derived automatically (see `harness/profile-name.sh`):
1. `$PIERIA_PROFILE` env var (highest priority)
2. Last segment of `git config --get remote.origin.url`, minus `.git`
3. `basename "$PWD"`

The name is then normalized to a lower-case `[a-z0-9-]` slug. The shim
(`ProfileResolver.java`) and the hook scripts use the same logic, so they always
agree on the profile.

## Pointing multiple harnesses at the same profile

Set `PIERIA_PROFILE=<slug>` in each harness's env (MCP server env for the shim,
shell env for hook scripts). Any harness targeting the same slug shares the same
memory store via the common daemon.

## Version verification

> VERIFY against current Claude Code docs (as of 2026-05):
> - Hook event names: `SessionStart`, `PreCompact`, `Stop`.
> - Environment variables available inside hook commands:
>   `CLAUDE_TRANSCRIPT_PATH`, `CLAUDE_SESSION_ID`.
> - The `claude mcp add` CLI syntax and `.mcp.json` format.
>
> These surfaces are evolving; check the Claude Code changelog before deploying
> to a team.

## Phase 5 follow-up

SPEC §10.5 calls for bundling the shim registration and all three hooks into a
single installable Claude Code plugin via a marketplace manifest (`claude plugin add`).
This is a Phase 5 deliverable; the manual steps above are the interim install path.
