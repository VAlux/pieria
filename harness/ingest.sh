#!/usr/bin/env sh
# ingest.sh — harness ingestion hook client for Pieria
#
# Reads a Claude Code session transcript (JSONL / NDJSON — one event object per line) from a file
# argument or stdin, derives the profile slug via profile-name.sh, and POSTs the RAW transcript to
# the daemon's /ingest/transcript endpoint. The daemon parses the JSONL server-side (Jackson), so
# this script needs no JSON tooling (no jq/python/awk) — only curl.
#
# FAIL-CLOSED CONTRACT: on any error (daemon unreachable, curl failure, non-2xx response) this
# script logs to stderr and exits 0. It must NEVER break or stall the harness session.
#
# Usage:
#   cat transcript.jsonl | sh harness/ingest.sh          # from stdin
#   sh harness/ingest.sh /path/to/transcript.jsonl       # from a file argument
#
# Environment variables:
#   PIERIA_DAEMON_URL  — daemon base URL (default: http://127.0.0.1:8077)
#   PIERIA_PROFILE     — explicit profile override (see profile-name.sh)
#   PIERIA_SESSION_ID  — session ID to tag the ingested messages (default: generated)
#   PIERIA_HARNESS     — harness id selecting the daemon-side transcript parser
#                        (default: claude-code; e.g. codex). The per-harness hook scripts set this.

set -e

# Resolve the directory containing this script so we can source profile-name.sh
# regardless of the caller's working directory.
_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

# Source the profile resolver so PIERIA_RESOLVED_PROFILE is set.
# shellcheck source=./profile-name.sh
. "${_SCRIPT_DIR}/profile-name.sh"

PROFILE="${PIERIA_RESOLVED_PROFILE:-default}"
DAEMON_URL="${PIERIA_DAEMON_URL:-http://127.0.0.1:8077}"

# Generate a simple session ID if not provided: timestamp + random suffix (no uuidgen dependency).
_default_session_id() {
  printf 'session-%s-%s' "$(date +%s)" "$(od -An -N4 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n' || printf '%04d' $$)"
}
SESSION_ID="${PIERIA_SESSION_ID:-$(_default_session_id)}"
HARNESS="${PIERIA_HARNESS:-claude-code}"

ENDPOINT="${DAEMON_URL}/v1/profiles/${PROFILE}/ingest/transcript?sessionId=${SESSION_ID}&harness=${HARNESS}"

# ---------------------------------------------------------------------------
# Determine the transcript source. We pass it to curl as --data-binary so the
# daemon receives the exact bytes of the JSONL file (no reshaping in shell).
# ---------------------------------------------------------------------------
if [ $# -ge 1 ] && [ -n "$1" ]; then
  if [ ! -f "$1" ]; then
    printf '[pieria/ingest] WARNING: transcript file not found: %s\n' "$1" >&2
    exit 0
  fi
  if [ ! -s "$1" ]; then
    printf '[pieria/ingest] WARNING: transcript file is empty: %s; nothing to ingest.\n' "$1" >&2
    exit 0
  fi
  DATA_ARG="@$1"
else
  if [ -t 0 ]; then
    printf '[pieria/ingest] WARNING: no transcript file or stdin data; nothing to ingest.\n' >&2
    exit 0
  fi
  DATA_ARG="@-"
fi

# ---------------------------------------------------------------------------
# POST to the daemon — fail closed on any error.
# set +e around curl so a curl failure does not abort the script.
# ---------------------------------------------------------------------------
_RESP_FILE="/tmp/pieria_ingest_response_$$.txt"
_ERR_FILE="/tmp/pieria_ingest_stderr_$$.txt"
trap 'rm -f "$_RESP_FILE" "$_ERR_FILE"' EXIT

set +e
HTTP_STATUS=$(curl \
  --silent \
  --show-error \
  --write-out '%{http_code}' \
  --output "$_RESP_FILE" \
  --max-time 30 \
  --header 'Content-Type: application/x-ndjson' \
  --data-binary "$DATA_ARG" \
  "$ENDPOINT" 2>"$_ERR_FILE")
CURL_EXIT=$?
set -e

if [ $CURL_EXIT -ne 0 ]; then
  printf '[pieria/ingest] WARNING: curl failed (exit %d) posting to %s\n' "$CURL_EXIT" "$ENDPOINT" >&2
  if [ -s "$_ERR_FILE" ]; then
    printf '[pieria/ingest] curl error: %s\n' "$(cat "$_ERR_FILE")" >&2
  fi
  exit 0
fi

case "$HTTP_STATUS" in
  2*)
    printf '[pieria/ingest] Ingested transcript into profile "%s" (HTTP %s)\n' "$PROFILE" "$HTTP_STATUS" >&2
    if [ -s "$_RESP_FILE" ]; then
      printf '[pieria/ingest] Response: %s\n' "$(cat "$_RESP_FILE")" >&2
    fi
    ;;
  *)
    # Loud, so a rejected payload is never silently mistaken for success.
    printf '[pieria/ingest] WARNING: daemon returned HTTP %s for profile "%s" at %s\n' "$HTTP_STATUS" "$PROFILE" "$ENDPOINT" >&2
    if [ -s "$_RESP_FILE" ]; then
      printf '[pieria/ingest] Response: %s\n' "$(cat "$_RESP_FILE")" >&2
    fi
    exit 0
    ;;
esac

exit 0
