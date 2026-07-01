#!/usr/bin/env sh
# session-start.sh — Codex CLI SessionStart hook (SPEC §10.3, §10.4).
#
# Fires when a Codex session opens. Fast-recalls a project-context primer from the daemon and prints
# it to stdout so Codex can surface it before the first turn. Mirrors harness/claude-code/session-start.sh.
#
# Delegates to the shared recall.sh, which uses the daemon's fast path (deterministic analysis, no
# synthesis) — fixing the previous version, which curled the full synthesized /recall with a 10s
# timeout (against a ~tens-of-seconds recall, so it always timed out) and dumped raw JSON.
#
# NOTE: Codex command hooks are command-only and fire on tool/command events, not on prompt
# submission, so there is no per-prompt recall here. This session-open primer plus the on-demand
# /pieria-recall command are the recall surfaces Codex supports. (Pieria no longer does per-prompt
# auto-recall on any harness — it was low-precision and taxed every turn.)
#
# VERIFY against current Codex CLI docs (as of 2026-05): the SessionStart event name and whether hook
# stdout is injected into the session context must be confirmed. These surfaces are recent and evolving.
#
# FAIL-CLOSED: recall.sh logs to stderr and exits 0 on any error, so the session starts regardless of
# whether the daemon or Ollama is reachable.
#
# Environment variables:
#   PIERIA_DAEMON_URL     — daemon base URL (default: http://127.0.0.1:8077)
#   PIERIA_PROFILE        — explicit profile override (see profile-name.sh)
#   PIERIA_RECALL_QUERY   — override the primer query (default: project context summary)
#   PIERIA_RECALL_LIMIT   — number of memories to inject (default: 10)
#   PIERIA_RECALL_TIMEOUT — recall curl --max-time seconds (default: 8)

set -e

_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
_HARNESS_DIR=$(cd "${_SCRIPT_DIR}/.." && pwd)

QUERY="${PIERIA_RECALL_QUERY:-What should I know about this project before starting a new session? Summarize key facts, active tasks, and recent decisions.}"
LIMIT="${PIERIA_RECALL_LIMIT:-10}"

sh "${_HARNESS_DIR}/recall.sh" "$QUERY" "$LIMIT"
exit 0
