#!/usr/bin/env sh
# pre-compact.sh — Claude Code PreCompact hook (SPEC §10.3, phase-4 step 6).
#
# Fires before Claude Code compacts the conversation context window. Ingests the
# current session transcript so memories are extracted and persisted before the
# raw messages are discarded.
#
# VERIFY against current Claude Code docs (as of 2026-05):
#   The PreCompact hook type and the environment variables it exposes (notably
#   CLAUDE_TRANSCRIPT_PATH and CLAUDE_SESSION_ID) must be confirmed against the
#   current Claude Code release notes. The PreCompact event name and payload
#   format may change across releases.
#
# FAIL-CLOSED: any error is logged to stderr and the hook exits 0 so compaction
# proceeds regardless of daemon availability.
#
# Environment variables set by Claude Code (verify in current docs):
#   CLAUDE_TRANSCRIPT_PATH — path to the current session transcript JSON file
#   CLAUDE_SESSION_ID      — current session identifier
#
# Environment variables recognized:
#   PIERIA_DAEMON_URL  — daemon base URL (default: http://127.0.0.1:8077)
#   PIERIA_PROFILE     — explicit profile override (see profile-name.sh)

set -e

_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
_HARNESS_DIR=$(cd "${_SCRIPT_DIR}/.." && pwd)

# Use CLAUDE_SESSION_ID if available, otherwise the ingest.sh default applies.
if [ -n "${CLAUDE_SESSION_ID:-}" ]; then
  export PIERIA_SESSION_ID="$CLAUDE_SESSION_ID"
fi

# Determine transcript source:
#   1. CLAUDE_TRANSCRIPT_PATH env var (set by Claude Code)
#   2. Fall back gracefully if not available
if [ -n "${CLAUDE_TRANSCRIPT_PATH:-}" ] && [ -f "$CLAUDE_TRANSCRIPT_PATH" ]; then
  sh "${_HARNESS_DIR}/ingest.sh" "$CLAUDE_TRANSCRIPT_PATH"
else
  printf '[pieria/pre-compact] WARNING: CLAUDE_TRANSCRIPT_PATH not set or file not found; skipping ingest.\n' >&2
  printf '[pieria/pre-compact] VERIFY: confirm the PreCompact hook exposes CLAUDE_TRANSCRIPT_PATH in current Claude Code docs.\n' >&2
  exit 0
fi

exit 0
