#!/usr/bin/env sh
# session-start.sh — Claude Code SessionStart hook (SPEC §10.3, phase-4 step 6).
#
# Fires once when Claude Code opens a session. Fast-recalls a project-context primer from the daemon
# and prints it to stdout so Claude's first response is primed with relevant prior memories. Claude
# Code injects a SessionStart hook's stdout into the session preamble.
#
# This delegates to the shared recall.sh, which uses the daemon's fast path (deterministic analysis,
# no synthesis) — fixing the previous version, which curled the full synthesized /recall with a 10s
# timeout (against a ~tens-of-seconds recall, so it always timed out) and dumped raw JSON.
#
# VERIFY against current Claude Code docs (as of 2026-05): the SessionStart hook type and the hook
# environment may change across releases.
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
