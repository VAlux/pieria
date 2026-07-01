#!/usr/bin/env sh
# stop.sh — Claude Code Stop hook (SPEC §10.3, phase-4 step 6).
#
# Fires when Claude Code ends a session (Stop event). Ingests the final session
# transcript so the full conversation is captured even if no compaction occurred.
#
# VERIFY against current Claude Code docs (as of 2026-05):
#   The Stop hook type and the environment variables it exposes (notably
#   CLAUDE_TRANSCRIPT_PATH and CLAUDE_SESSION_ID) must be confirmed against the
#   current Claude Code release notes. The Stop event name and payload format
#   may change across releases.
#
# FAIL-CLOSED: any error is logged to stderr and the hook exits 0 so session
# shutdown proceeds regardless of daemon availability.
#
# Environment variables set by Claude Code (verify in current docs):
#   CLAUDE_TRANSCRIPT_PATH — path to the final session transcript JSON file
#   CLAUDE_SESSION_ID      — current session identifier
#
# Environment variables recognized:
#   PIERIA_DAEMON_URL  — daemon base URL (default: http://127.0.0.1:8077)
#   PIERIA_PROFILE     — explicit profile override (see profile-name.sh)

set -e

_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
_HARNESS_DIR=$(cd "${_SCRIPT_DIR}/.." && pwd)

# Select the Claude Code transcript parser on the daemon side.
export PIERIA_HARNESS="claude-code"

# Use CLAUDE_SESSION_ID if available.
if [ -n "${CLAUDE_SESSION_ID:-}" ]; then
  export PIERIA_SESSION_ID="$CLAUDE_SESSION_ID"
fi

# Determine transcript source.
if [ -n "${CLAUDE_TRANSCRIPT_PATH:-}" ] && [ -f "$CLAUDE_TRANSCRIPT_PATH" ]; then
  sh "${_HARNESS_DIR}/ingest.sh" "$CLAUDE_TRANSCRIPT_PATH"
else
  printf '[pieria/stop] WARNING: CLAUDE_TRANSCRIPT_PATH not set or file not found; skipping final ingest.\n' >&2
  printf '[pieria/stop] VERIFY: confirm the Stop hook exposes CLAUDE_TRANSCRIPT_PATH in current Claude Code docs.\n' >&2
  exit 0
fi

exit 0
