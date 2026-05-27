# Phase 4 - Daemon, MCP Gateway, And Local Harness Integration

## Objective

Harden Pieria as a local daemon and make it usable from agent harnesses through a thin MCP stdio gateway plus lifecycle hook assets. Claude Code integration is first-class in this phase, with OpenCode and Codex documented as equivalent configurations.

## Scope

- Daemon hardening for local mode.
- MCP stdio gateway for model-facing tools.
- Profile mapping and harness configuration assets.
- No native installer or OS service automation yet; that is Phase 5.
- No server-mode auth or remote tenancy yet; that is Phase 6.

## Implementation Sequence

1. Harden daemon defaults.
   - Bind to `127.0.0.1` by default.
   - Make host and port configurable through `pieria.daemon.*` properties.
   - Expose `/pieria-health` with lightweight database and model-provider status.
   - Use explicit transaction boundaries for embedded writes.
   - Keep SQLite writes serialized through service methods owned by the daemon.

2. Stabilize REST API behavior.
   - Ensure each endpoint has clear request and response schemas.
   - Add consistent error response bodies for validation, daemon unavailable, model unavailable, and missing memory cases.
   - Add compatibility tests so the MCP gateway can rely on stable JSON.
   - Document localhost-only security assumptions for local mode.

3. Add MCP stdio gateway.
   - Implement the gateway as thin client code in the existing project unless a later packaging need justifies a separate artifact.
   - Expose model-facing tools:
     - `recall` -> `POST /v1/profiles/{name}/recall`
     - `remember` -> `POST /v1/profiles/{name}/memories`
     - `list` -> `GET /v1/profiles/{name}/memories`
     - `forget` -> `DELETE /v1/profiles/{name}/memories/{id}`
   - Do not expose `ingest` as a model-facing MCP tool.
   - Forward requests to the daemon over localhost HTTP.
   - Return concise tool errors when the daemon is not running.

4. Implement profile mapping.
   - Support explicit profile from environment or config, for example `PIERIA_PROFILE`.
   - Fall back to git remote-derived project name when available.
   - Fall back to the current project directory name when no git remote exists.
   - Keep profile normalization identical between hooks and the MCP gateway.
   - Add tests for remote URL variants, detached directories, and explicit overrides.

5. Add harness-driven ingestion hooks.
   - Provide a script or small client command that sends transcript JSON to `/v1/profiles/{name}/ingest`.
   - Ensure hook ingestion includes session ID, timestamp, role, and content.
   - Make hooks fail closed with logs rather than breaking the harness session.
   - Keep hook code free of secrets and machine-specific paths.

6. Provide Claude Code installation assets first.
   - MCP registration snippet or plugin assets for `recall`, `remember`, `list`, and `forget`.
   - `SessionStart` hook to optionally recall project context.
   - `PreCompact` hook to ingest the current transcript before compaction.
   - `Stop` hook to ingest final session state.
   - Include defaults that point to the local daemon and derived profile name.

7. Document OpenCode and Codex snippets.
   - OpenCode: MCP config, compaction hook equivalent, and system transform/session bootstrap guidance.
   - Codex: MCP server config, session-start recall, and stop-hook ingestion.
   - Clearly mark any harness event support that must be verified against current harness docs before release.

8. Add local integration smoke tests.
   - Start the daemon on a random local port in tests.
   - Run MCP gateway calls against fake or seeded memory data.
   - Validate hook command payloads without depending on a real harness.

## Tests

- API compatibility tests for all gateway-facing endpoint JSON contracts.
- Unit tests for profile mapping and config precedence.
- MCP gateway tests with a fake daemon HTTP server.
- Hook payload tests using transcript fixtures.
- Daemon tests for `127.0.0.1` default binding and `/pieria-health`.
- Run `./gradlew test`.

## Acceptance Criteria

- The daemon is safe for local default use and binds localhost only unless explicitly configured otherwise.
- `/pieria-health` works without invoking expensive model calls.
- MCP tools can recall, remember, list, and forget through the daemon.
- Profile mapping is deterministic and shared by the gateway and hooks.
- Claude Code setup assets exist, with OpenCode and Codex equivalents documented.

## Risks And Follow-Ups

- Harness hook APIs change over time; installation docs should identify the verified harness versions.
- The gateway should remain thin. Business logic belongs in daemon services so every harness behaves the same way.
- Phase 5 should turn these assets into an installable distribution with OS service management.
