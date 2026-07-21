#!/usr/bin/env sh
# recall.sh — harness fast-recall client for Pieria.
#
# Resolves the profile slug via profile-name.sh, fast-recalls the daemon for memories relevant to a
# query, and prints a compact text block to stdout for a harness hook to inject into the agent's
# context. "Fast" means the daemon skips model-based query analysis and synthesis (see RecallRequest
# `fast`), returning the retrieved memories in ~1-3s instead of tens of seconds.
#
# FAIL-CLOSED CONTRACT: on any error (daemon unreachable/degraded, missing python3, curl failure,
# non-2xx) this script logs to stderr and exits 0 with NO stdout, so the calling hook never breaks or
# stalls the session.
#
# Usage:
#   sh harness/recall.sh "<query>" [limit]
#
# Environment variables:
#   PIERIA_DAEMON_URL     — daemon base URL (default: http://127.0.0.1:8077)
#   PIERIA_PROFILE        — explicit profile override (see profile-name.sh)
#   PIERIA_RECALL_TIMEOUT — recall curl --max-time seconds (default: 8)

set -e

QUERY="$1"
LIMIT="${2:-5}"
if [ -z "$QUERY" ]; then
  exit 0
fi

# Resolve the directory containing this script so we can source profile-name.sh
# regardless of the caller's working directory.
_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=./profile-name.sh
. "${_SCRIPT_DIR}/profile-name.sh"
PROFILE="${PIERIA_RESOLVED_PROFILE:-default}"

DAEMON_URL="${PIERIA_DAEMON_URL:-http://127.0.0.1:8077}"
TIMEOUT="${PIERIA_RECALL_TIMEOUT:-8}"
HARNESS="${3:-${PIERIA_HARNESS:-unknown}}"

# python3 is required for safe JSON escaping of an arbitrary query; degrade to no-op without it.
if ! command -v python3 >/dev/null 2>&1; then
  printf '[pieria/recall] python3 not found — skipping recall injection.\n' >&2
  exit 0
fi

# Pre-flight: only attempt the (embedding-touching) recall when the daemon reports healthy. This keeps
# the common "daemon down" case fast (a 2s probe) instead of waiting out the recall timeout.
set +e
HEALTH=$(curl --silent --max-time 2 "${DAEMON_URL}/pieria-health" 2>/dev/null)
set -e
case "$HEALTH" in
  *'"status":"up"'*) : ;;
  *)
    printf '[pieria/recall] daemon not healthy — skipping recall injection.\n' >&2
    exit 0
    ;;
esac

# Build the request body with python3 so the query (which may contain quotes/newlines) is safely
# JSON-escaped. fast=true selects the low-latency injection path on the daemon.
PAYLOAD=$(QUERY="$QUERY" LIMIT="$LIMIT" python3 -c \
  'import json,os; print(json.dumps({"query":os.environ["QUERY"],"limit":int(os.environ["LIMIT"]),"fast":True}))') \
  || exit 0

# Ask for text/plain: the daemon returns a ready-to-inject block (or 204/empty when nothing recalled).
set +e
RESPONSE=$(curl \
  --silent \
  --show-error \
  --max-time "$TIMEOUT" \
  --header 'Content-Type: application/json' \
  --header 'Accept: text/plain' \
  --header 'X-Pieria-Client: hook' \
  --header "X-Pieria-Harness: ${HARNESS}" \
  --header 'X-Pieria-Channel: hook' \
  --data "$PAYLOAD" \
  "${DAEMON_URL}/v1/profiles/${PROFILE}/recall" 2>/dev/null)
CURL_EXIT=$?
set -e

if [ "$CURL_EXIT" -ne 0 ]; then
  printf '[pieria/recall] recall failed (curl exit %d) — proceeding without injection.\n' "$CURL_EXIT" >&2
  exit 0
fi

if [ -n "$RESPONSE" ]; then
  printf '%s\n' "$RESPONSE"
fi
exit 0
