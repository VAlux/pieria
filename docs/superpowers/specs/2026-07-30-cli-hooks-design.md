# Absorbing the harness hooks into the CLI

**Date:** 2026-07-30
**Status:** Approved, ready for implementation planning

## Problem

Pieria's harness integration runs through eleven POSIX shell scripts under `harness/`. They depend
on `sh`, `curl`, `python3`, `sed`, `basename`, and `git`, and the installers wire them into harness
config as the literal string `sh <path>/session-start.sh`.

Stock Windows has none of that. This is the deepest of the blockers identified in the Windows
support investigation: the other gaps (CI matrix, Tree-sitter MSVC exports, daemon lifecycle) are
about producing and running binaries, whereas this one means an *installed* Pieria still cannot wire
itself into a harness.

The scripts are also a maintenance liability today, independent of Windows. `harness/profile-name.sh`
reimplements `ProfileResolver.java` — the same precedence and the same slug normalization, written a
second time in `sed`. `recall.sh` and `remember.sh` shell out to `python3` purely to JSON-escape a
string. `recall.sh` still sends a `fast: true` field that was removed from `RecallRequest` when the
`RecallMode` enum landed; it is harmless only because the `text/plain` recall endpoint hardcodes
`RecallMode.EVIDENCE` and ignores the request's mode.

## Approach

Move the logic into the `pieria` CLI as a hidden `hook` command group. The harness config then
invokes the native binary directly, and the shell scripts are deleted.

### Why the CLI must read the environment itself

An installer could in principle emit a fully-parameterized command line:

```
pieria hook ingest --transcript "$CLAUDE_TRANSCRIPT_PATH"
```

This does not work. `$VAR` expansion requires a shell; `cmd.exe` uses `%VAR%`, and the harness hook
runners do not shell-expand on Windows. Any design that puts a variable reference in the emitted
command string reintroduces the dependency it is trying to remove.

Therefore the emitted command contains only literals — an absolute binary path and fixed
subcommand names — and the CLI reads `CLAUDE_TRANSCRIPT_PATH` and its siblings from its own
environment. This constraint is what selects the command surface below.

## Command surface

The group is registered with `hidden = true`: these commands are machine-invoked and would only
clutter `pieria --help`.

```
pieria hook claude-code session-start        # stdout: injection block
pieria hook claude-code pre-compact          # ┐
pieria hook claude-code stop                 # ├ all three ingest the transcript
pieria hook claude-code session-end          # ┘
pieria hook codex session-start
pieria hook codex stop
pieria hook opencode ingest                  # transcript on stdin
pieria hook opencode recall-transform        # stdin passthrough, then append recall
pieria hook recall <query> [--limit N] [--harness id]
pieria hook remember <text> [--harness id]
```

Lifecycle events are harness-scoped and take no arguments — all input comes from the environment.
`recall` and `remember` sit at the group's top level because they are not lifecycle events: they
back the `/pieria-recall` and `/pieria-remember` slash commands and take user input. They stay in
the `hook` group rather than getting a group of their own; two hidden groups for six commands is
worse than one slightly loose label.

## Components

### Additions to `shared`

`DaemonTransport` already supports per-call timeouts but only serializes JSON bodies. It gains:

```java
String postRaw(String path, byte[] body, String contentType, String accept, Duration timeout)
```

`ProfileClient` gains two methods built on it:

- `IngestResponse ingestTranscript(String name, String sessionId, String harness, byte[] ndjson)`
  — POSTs raw bytes as `application/x-ndjson` to `/ingest/transcript`. A null or blank `sessionId`
  is omitted from the query string so the daemon generates one.
- `Optional<String> recallText(String name, RecallRequest request)` — sends `Accept: text/plain`,
  returns the injection block, maps `204 No Content` to `Optional.empty()`.

### New package `cli/modules/hook`

Logic, free of picocli so it is directly unit-testable.

- **`HarnessHookSpec`** — a record per harness: parser id, ordered transcript env-var candidates
  (Codex probes three in order), session-id env var, and the default primer query. One constant per
  harness, colocated with that harness's installer.
- **`HookContext`** — resolves profile (via `ProfileResolver`), daemon URL (via `DaemonUrls`), and
  `ClientIdentity`. Takes an env accessor function so tests inject a fake map, matching the existing
  `PathResolver` pattern.
- **`TranscriptIngestor`**, **`ContextRecaller`**, **`MemoryPinner`** — one per verb. Each returns a
  `HookOutcome` rather than printing, so formatting stays in the command layer.

### New package `cli/command/hook`

Thin picocli classes, roughly twenty lines each, consistent with the twelve existing thin classes
under `command/profile/`. The three Claude Code ingest events share one class via picocli aliases,
using the invoked name for its diagnostic prefix.

`AbstractHookCommand` provides the shared contract. It deliberately does **not** extend
`AbstractProfileCommand`: that base maps failures to exit codes 1/3/4, which would break a harness
session.

## Fail-closed contract

Every hook command wraps its work in `try { ... } catch (Throwable)` and returns 0
unconditionally. This is the single most important behavior to preserve: a hook must never break or
stall a session, regardless of daemon or model availability.

Stream discipline, matching the scripts and already supported by `Logger` (`info` → stdout,
`error` → stderr):

- **stdout** carries only the recall injection block and the remember confirmation.
- **stderr** carries every diagnostic.

Preserved behaviors:

- **Health pre-flight** before recall and remember — a 2s probe via `HealthClient`, so a
  down daemon fails in 2s rather than waiting out the full timeout.
- **Timeouts** — health 2s, recall 8s, remember 8s, ingest 30s. Constants, not configurable.
- **Type-prefix parsing** on remember: a leading `fact:`, `instruction:`, `event:`, or `task:`
  token sets the type; default `fact`; a single leading space after the colon is dropped.
- **Missing or empty transcript** — one stderr warning, exit 0, no request issued.

Two simplifications fall out. `ingest.sh` hand-rolls a session id from `date` and `/dev/urandom`;
the daemon already generates `session-<UUID>` when `sessionId` is absent, so the CLI omits it.
And `python3` disappears entirely — Jackson does the JSON escaping it was shelled out for.

## Environment variables

Three distinct categories, treated differently.

**Dropped** — hook-script tuning knobs that nothing else reads:
`PIERIA_RECALL_QUERY`, `PIERIA_RECALL_LIMIT`, `PIERIA_RECALL_TIMEOUT`, `PIERIA_REMEMBER_TIMEOUT`,
`PIERIA_SESSION_ID`, `PIERIA_HARNESS`.

The primer query and limit become constants in each `HarnessHookSpec`; timeouts become constants;
the session id comes from the harness's own env var. For the harness-scoped lifecycle commands the
harness id is implied by which subcommand ran; for the two harness-agnostic verbs it comes from the
`--harness` flag, which the slash-command templates pass explicitly. `hook recall --limit` remains
as a flag.

**Kept** — `PIERIA_DAEMON_URL` and `PIERIA_PROFILE`. These are not hook-specific: `DaemonUrls.resolve()`
and `ProfileResolver.create()` read them for every CLI command, and the generated `.mcp.json` sets
`PIERIA_DAEMON_URL` for the gateway. Suppressing them inside hook commands would mean writing code
to defeat components being reused wholesale.

**Not overrides** — `CLAUDE_TRANSCRIPT_PATH`, `CLAUDE_SESSION_ID`, `CODEX_TRANSCRIPT_PATH`,
`CODEX_SESSION_TRANSCRIPT`, `CODEX_ROLLOUT_PATH`, `CODEX_SESSION_ID`. These are the sole input
channel from the harness. Reading them is the entire reason this logic belongs in the CLI.

## Installer changes

`hookCommand()` stops emitting `sh <path>` and emits the CLI binary path plus literal arguments.
`PathResolver` gains `cliCommand()` alongside the existing `gatewayCommand()`, resolving
`pieria` / `pieria.exe` by the same precedence (`PIERIA_HOME` → sibling of the running executable →
OS default).

**Quoting.** The harness `command` field is a single string, and a Windows install path is routinely
`C:\Users\First Last\AppData\...`. The emitted string must quote the executable path or it breaks
for any user whose account name contains a space. This is covered by an explicit test.

Per harness:

- **Claude Code** — four `settings.json` hook entries plus two `.claude/commands/*.md` templates.
- **Codex** — two `config.toml` hook entries. Its command templates are model-mediated (they
  instruct the model to call the MCP tool, never shell) and do not change.
- **OpenCode** — `experimental.hook.session.compacting.plugin` → `hook opencode ingest`;
  `experimental.chat.system.transform` → `hook opencode recall-transform`. Its command templates
  are likewise model-mediated and unchanged.

The `recall-transform` contract is preserved exactly: read the original system prompt from stdin,
echo it unchanged, then append the recalled block under a `---` separator — appending nothing when
recall yields nothing.

### Migration

Existing installs point at `sh .../claude-code/stop.sh`. Migration rides the mechanism that already
exists: `isPieriaHookCommand()` is extended to match both the legacy `sh .../<script>.sh` form and
the new `... hook <harness> <event>` form. `stripLegacyHooks()` then prunes stale entries on
re-install, and uninstall removes both forms. A user re-running `pieria harness install` is migrated
with no manual edit.

Slash-command templates swap the `<PIERIA_HARNESS_DIR>` placeholder for `<PIERIA_BIN>`, and
`allowed-tools: Bash(sh:*)` for `Bash(pieria:*)`.

## Deletions

- The eleven `harness/**/*.sh` files.
- `HookAssetWriter` and its tests.
- `HarnessInstaller.requiredScriptResources()` — no implementation needs it once scripts are gone.
- The `*.sh` includes in `stageHarnessAssets` (`modules/cli/build.gradle.kts`). The task remains for
  the `commands/*.md` templates.
- `WiringContext.harnessDir` — dead once no template substitutes a script path.
- `deployLocal`'s `preserve { include("harness/...") }` block, which exists only to stop `Sync` from
  deleting extracted scripts. Dropping it lets the next deploy clean out orphaned scripts, which is
  the desired outcome.

`harness/README.md` is rewritten to document the hook command contract rather than the script
contract.

## Testing

`StubDaemon` already exists as a localhost HTTP stub, which AGENTS.md prescribes over injecting fake
clients.

New tests:

- `HookContextTests` — profile and daemon-URL resolution against an injected env map.
- `TranscriptIngestorTests` — file path, stdin, missing file, empty file, non-2xx response.
- `ContextRecallerTests` — 200 block reaches stdout, 204 produces nothing, daemon down produces
  nothing.
- `MemoryPinnerTests` — the type-prefix parsing table, including the no-prefix and empty-content
  cases.
- One fail-closed test per hook command: daemon unreachable still exits 0 with clean stdout.

Extended tests:

- `ClaudeCodeInstallerTests`, `CodexInstallerTests`, `OpenCodeInstallerTests` — emitted command
  shape, quoting with a space in the install path, and migration of a legacy `sh ...` entry.

`./gradlew test` must pass before commit.

## Scope

This spec covers the hook absorption only, verified on macOS. Explicitly **out of scope**, tracked
as separate follow-ups from the Windows investigation:

1. Enabling the `windows-latest` CI matrix entry.
2. Tree-sitter MSVC symbol exports and prebuilt Windows grammar DLLs.
3. `pieria update` on Windows (zip extraction, rename-then-replace for locked executables).
4. Windows daemon lifecycle (Scheduled Task detection, detached spawn).
5. Backslash escaping tests for generated JSON/TOML config.

Removing the `sh` / `curl` / `python3` dependency is a prerequisite for all of them, and is a net
simplification on macOS and Linux regardless of whether Windows support is ever finished.
