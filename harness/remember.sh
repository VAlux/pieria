#!/usr/bin/env sh
# remember.sh — harness explicit-memory client for Pieria.
#
# Resolves the profile slug via profile-name.sh and POSTs a single memory to the daemon's
# /memories endpoint (the same write the MCP `remember` tool performs). Intended to back a
# user-triggered slash command (/pieria-remember) so a human can deterministically pin a memory
# without depending on the model choosing to call the MCP tool.
#
# Unlike the ingest/recall hook clients (which are background and fully silent on failure), this is
# an explicit user action: on failure it prints a short notice to stdout so the user knows the
# memory did NOT persist. It still exits 0 so it never breaks the harness.
#
# Usage:
#   sh harness/remember.sh "<content>"
#   sh harness/remember.sh "instruction: always run ./gradlew test before committing"
#     A leading "fact:" / "instruction:" / "event:" / "task:" token sets the memory type
#     (default: fact). Everything after the token is the content.
#
# Environment variables:
#   PIERIA_DAEMON_URL       — daemon base URL (default: http://127.0.0.1:8077)
#   PIERIA_PROFILE          — explicit profile override (see profile-name.sh)
#   PIERIA_REMEMBER_TIMEOUT — curl --max-time seconds (default: 8)

set -e

RAW="$1"
if [ -z "$RAW" ]; then
  printf 'usage: /pieria-remember [fact:|instruction:|event:|task:] <content>\n'
  exit 0
fi

# Split an optional leading "<type>:" token off the content.
TYPE="fact"
CONTENT="$RAW"
case "$RAW" in
  fact:*|instruction:*|event:*|task:*)
    TYPE="${RAW%%:*}"
    CONTENT="${RAW#*:}"
    CONTENT="${CONTENT# }"  # drop a single leading space after the colon
    ;;
esac

if [ -z "$CONTENT" ]; then
  printf 'usage: /pieria-remember [fact:|instruction:|event:|task:] <content>\n'
  exit 0
fi

# Resolve the directory containing this script so we can source profile-name.sh
# regardless of the caller's working directory.
_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=./profile-name.sh
. "${_SCRIPT_DIR}/profile-name.sh"
PROFILE="${PIERIA_RESOLVED_PROFILE:-default}"

DAEMON_URL="${PIERIA_DAEMON_URL:-http://127.0.0.1:8077}"
TIMEOUT="${PIERIA_REMEMBER_TIMEOUT:-8}"
HARNESS="${2:-${PIERIA_HARNESS:-unknown}}"

# python3 is required for safe JSON escaping of arbitrary content.
if ! command -v python3 >/dev/null 2>&1; then
  printf '[pieria/remember] python3 not found — cannot store memory.\n'
  exit 0
fi

# Pre-flight health probe so "daemon down" fails fast with a clear message.
set +e
HEALTH=$(curl --silent --max-time 2 "${DAEMON_URL}/pieria-health" 2>/dev/null)
set -e
case "$HEALTH" in
  *'"status":"up"'*) : ;;
  *)
    printf '[pieria/remember] daemon not reachable at %s — memory NOT stored.\n' "$DAEMON_URL"
    exit 0
    ;;
esac

PAYLOAD=$(TYPE="$TYPE" CONTENT="$CONTENT" python3 -c \
  'import json,os; print(json.dumps({"type":os.environ["TYPE"],"content":os.environ["CONTENT"]}))') \
  || { printf '[pieria/remember] failed to build request — memory NOT stored.\n'; exit 0; }

_RESP_FILE="/tmp/pieria_remember_response_$$.txt"
trap 'rm -f "$_RESP_FILE"' EXIT

set +e
HTTP_STATUS=$(curl \
  --silent \
  --show-error \
  --write-out '%{http_code}' \
  --output "$_RESP_FILE" \
  --max-time "$TIMEOUT" \
  --header 'Content-Type: application/json' \
  --header 'X-Pieria-Client: hook' \
  --header "X-Pieria-Harness: ${HARNESS}" \
  --header 'X-Pieria-Channel: hook' \
  --data "$PAYLOAD" \
  "${DAEMON_URL}/v1/profiles/${PROFILE}/memories" 2>/dev/null)
CURL_EXIT=$?
set -e

if [ "$CURL_EXIT" -ne 0 ]; then
  printf '[pieria/remember] request failed (curl exit %d) — memory NOT stored.\n' "$CURL_EXIT"
  exit 0
fi

case "$HTTP_STATUS" in
  2*)
    printf '✓ Pieria remembered (%s) in profile "%s": %s\n' "$TYPE" "$PROFILE" "$CONTENT"
    ;;
  *)
    printf '[pieria/remember] daemon returned HTTP %s — memory NOT stored.\n' "$HTTP_STATUS"
    if [ -s "$_RESP_FILE" ]; then
      printf '[pieria/remember] Response: %s\n' "$(cat "$_RESP_FILE")"
    fi
    ;;
esac

exit 0
