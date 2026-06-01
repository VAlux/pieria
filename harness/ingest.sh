#!/usr/bin/env sh
# ingest.sh — harness ingestion hook client for Pieria
#
# Reads a transcript payload (JSON) from stdin or a file argument, derives the profile
# slug via profile-name.sh, and POSTs to the daemon's /ingest endpoint.
#
# FAIL-CLOSED CONTRACT: on any error (daemon unreachable, curl failure, non-2xx response)
# this script logs to stderr and exits 0. It must NEVER break or stall the harness session.
#
# Usage:
#   # From stdin (pipe):
#   cat transcript.json | sh harness/ingest.sh
#
#   # From a file argument:
#   sh harness/ingest.sh /path/to/transcript.json
#
# Environment variables:
#   PIERIA_DAEMON_URL  — daemon base URL (default: http://127.0.0.1:8077)
#   PIERIA_PROFILE     — explicit profile override (see profile-name.sh)
#   PIERIA_SESSION_ID  — session ID to include in the payload (default: generated uuid-like value)
#
# ---------------------------------------------------------------------------
# HARNESS-SPECIFIC ADAPTATION NOTE
# ---------------------------------------------------------------------------
# The transcript-to-payload mapping below expects the input to be a JSON object
# with a "messages" array, each entry having "role" and "content" fields, and
# optionally a top-level "sessionId":
#
#   {
#     "sessionId": "optional-session-id",
#     "messages": [
#       { "role": "user",      "content": "..." },
#       { "role": "assistant", "content": "..." }
#     ]
#   }
#
# If your harness produces a different transcript format, adapt the
# _pieria_build_payload() function below to reshape it before sending.
# Claude Code's PreCompact/Stop hooks pass $CLAUDE_TRANSCRIPT_PATH (a file
# containing the session JSON); adapt the reading logic for other harnesses.
# ---------------------------------------------------------------------------

set -e

# Resolve the directory containing this script so we can source profile-name.sh
# regardless of the caller's working directory.
_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

# Source the profile resolver so PIERIA_RESOLVED_PROFILE is set.
# shellcheck source=./profile-name.sh
. "${_SCRIPT_DIR}/profile-name.sh"

PROFILE="${PIERIA_RESOLVED_PROFILE:-default}"
DAEMON_URL="${PIERIA_DAEMON_URL:-http://127.0.0.1:8077}"
ENDPOINT="${DAEMON_URL}/v1/profiles/${PROFILE}/ingest"

# Generate a simple session ID if not provided: timestamp + random suffix (no uuidgen dependency).
_default_session_id() {
  printf 'session-%s-%s' "$(date +%s)" "$(od -An -N4 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n' || printf '%04d' $$)"
}
SESSION_ID="${PIERIA_SESSION_ID:-$(_default_session_id)}"

# ---------------------------------------------------------------------------
# _pieria_build_payload TRANSCRIPT_JSON SESSION_ID
# Shapes the raw transcript into the /ingest request body.
# Expected input: JSON with "messages" array of {role, content} objects.
# Override this function for harness-specific transcript formats.
# ---------------------------------------------------------------------------
_pieria_build_payload() {
  _transcript="$1"
  _sid="$2"

  # If the transcript already has a top-level sessionId, use it; otherwise inject ours.
  # We use sed for a minimal dependency footprint (no jq required).
  # For production harnesses with complex JSON, swap this for a proper jq/python transform.

  # Check if the transcript already contains a sessionId key.
  if printf '%s' "$_transcript" | grep -q '"sessionId"'; then
    # Transcript already has sessionId — use as-is.
    printf '%s' "$_transcript"
  else
    # Inject sessionId into the top-level JSON object.
    # Simple approach: insert after the opening brace.
    printf '%s' "$_transcript" | sed 's/^[[:space:]]*{/{/' | \
      sed "s/^{/{ \"sessionId\": \"${_sid}\", /"
  fi
}

# ---------------------------------------------------------------------------
# Read the transcript from a file argument or stdin
# ---------------------------------------------------------------------------
if [ $# -ge 1 ] && [ -n "$1" ]; then
  if [ ! -f "$1" ]; then
    printf '[pieria/ingest] WARNING: transcript file not found: %s\n' "$1" >&2
    exit 0
  fi
  TRANSCRIPT=$(cat "$1")
else
  # Read from stdin; if stdin is a terminal (no pipe), treat as empty.
  if [ -t 0 ]; then
    printf '[pieria/ingest] WARNING: no transcript file or stdin data; nothing to ingest.\n' >&2
    exit 0
  fi
  TRANSCRIPT=$(cat)
fi

if [ -z "$TRANSCRIPT" ]; then
  printf '[pieria/ingest] WARNING: empty transcript; nothing to ingest.\n' >&2
  exit 0
fi

PAYLOAD=$(_pieria_build_payload "$TRANSCRIPT" "$SESSION_ID")

# ---------------------------------------------------------------------------
# POST to the daemon — fail closed on any error
# ---------------------------------------------------------------------------
# We deliberately use set +e around the curl call so a curl failure does not
# abort the script (we re-enable set -e after).
set +e

HTTP_STATUS=$(curl \
  --silent \
  --show-error \
  --write-out '%{http_code}' \
  --output /tmp/pieria_ingest_response_$$.txt \
  --max-time 30 \
  --header 'Content-Type: application/json' \
  --data "$PAYLOAD" \
  "$ENDPOINT" 2>/tmp/pieria_ingest_stderr_$$.txt)
CURL_EXIT=$?

set -e

# Cleanup temp files on exit
trap 'rm -f /tmp/pieria_ingest_response_$$.txt /tmp/pieria_ingest_stderr_$$.txt' EXIT

if [ $CURL_EXIT -ne 0 ]; then
  printf '[pieria/ingest] WARNING: curl failed (exit %d) posting to %s\n' "$CURL_EXIT" "$ENDPOINT" >&2
  if [ -s /tmp/pieria_ingest_stderr_$$.txt ]; then
    printf '[pieria/ingest] curl error: %s\n' "$(cat /tmp/pieria_ingest_stderr_$$.txt)" >&2
  fi
  exit 0
fi

case "$HTTP_STATUS" in
  2*)
    printf '[pieria/ingest] Ingested transcript into profile "%s" (HTTP %s)\n' "$PROFILE" "$HTTP_STATUS" >&2
    ;;
  *)
    printf '[pieria/ingest] WARNING: daemon returned HTTP %s for profile "%s"\n' "$HTTP_STATUS" "$PROFILE" >&2
    if [ -s /tmp/pieria_ingest_response_$$.txt ]; then
      printf '[pieria/ingest] Response: %s\n' "$(cat /tmp/pieria_ingest_response_$$.txt)" >&2
    fi
    exit 0
    ;;
esac

exit 0
