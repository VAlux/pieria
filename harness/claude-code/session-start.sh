#!/usr/bin/env sh
# session-start.sh — Claude Code SessionStart hook (SPEC §10.3, phase-4 step 6).
#
# Fires once when Claude Code opens a session. Calls /recall on the daemon with a
# project-context query and injects the result into the environment so Claude's first
# response is primed with relevant prior memories.
#
# VERIFY against current Claude Code docs (as of 2026-05):
#   The SessionStart hook type and the CLAUDE_PROJECT_DIR environment variable available
#   inside hook commands must be confirmed against the current Claude Code release notes.
#   Hook environments and event names may change across releases.
#
# FAIL-CLOSED: any error is logged to stderr and the hook exits 0 so the session starts
# regardless of whether the daemon is reachable.
#
# Environment variables recognized:
#   PIERIA_DAEMON_URL  — daemon base URL (default: http://127.0.0.1:8077)
#   PIERIA_PROFILE     — explicit profile override (see profile-name.sh)
#   PIERIA_RECALL_QUERY — override the recall query (default: project context summary)
#   PIERIA_RECALL_LIMIT — number of memories to consider (default: 10)

set -e

_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
_HARNESS_DIR=$(cd "${_SCRIPT_DIR}/.." && pwd)

# Resolve profile
. "${_HARNESS_DIR}/profile-name.sh"
PROFILE="${PIERIA_RESOLVED_PROFILE:-default}"

DAEMON_URL="${PIERIA_DAEMON_URL:-http://127.0.0.1:8077}"
ENDPOINT="${DAEMON_URL}/v1/profiles/${PROFILE}/recall"
LIMIT="${PIERIA_RECALL_LIMIT:-10}"
QUERY="${PIERIA_RECALL_QUERY:-What should I know about this project before starting a new session? Summarize key facts, active tasks, and recent decisions.}"

PAYLOAD=$(printf '{"query":"%s","limit":%d}' "$QUERY" "$LIMIT")

set +e
RESPONSE=$(curl \
  --silent \
  --show-error \
  --max-time 10 \
  --header 'Content-Type: application/json' \
  --data "$PAYLOAD" \
  "$ENDPOINT" 2>/tmp/pieria_recall_stderr_$$.txt)
CURL_EXIT=$?
set -e

trap 'rm -f /tmp/pieria_recall_stderr_$$.txt' EXIT

if [ $CURL_EXIT -ne 0 ]; then
  printf '[pieria/session-start] WARNING: recall failed (curl exit %d) — proceeding without prior memories.\n' "$CURL_EXIT" >&2
  exit 0
fi

if [ -n "$RESPONSE" ]; then
  printf '[pieria/session-start] Prior context recalled for profile "%s".\n' "$PROFILE" >&2
  # Print the answer field so Claude Code can surface it in the session preamble.
  # Claude Code injects hook stdout into the session context when the hook type is "command".
  # Adjust the extraction method if the response shape changes.
  printf '%s\n' "$RESPONSE"
fi

exit 0
