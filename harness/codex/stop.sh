#!/usr/bin/env sh
# stop.sh — Codex CLI Stop hook (SPEC §10.3, §10.4).
#
# Fires when a Codex session ends. Ingests the final transcript so the conversation
# is captured (Codex has no compaction-specific event as of 2026-05, so Stop is the
# single ingestion point). Mirrors harness/claude-code/stop.sh.
#
# VERIFY against current Codex CLI docs (as of 2026-05):
#   The Stop event name, the [[hooks]] table structure, and the env var exposing the
#   transcript path must be confirmed. Codex command hooks are recent and command-only.
#   We probe the common candidates below; adjust once the canonical name is confirmed.
#
# FAIL-CLOSED: any error is logged to stderr and the hook exits 0 so Codex session
# shutdown is never blocked.
#
# Environment variables recognized:
#   PIERIA_DAEMON_URL  — daemon base URL (default: http://127.0.0.1:8077)
#   PIERIA_PROFILE     — explicit profile override (see profile-name.sh)

set -e

_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
_HARNESS_DIR=$(cd "${_SCRIPT_DIR}/.." && pwd)

# Carry through a session id if Codex exposes one (verify the env var name).
if [ -n "${CODEX_SESSION_ID:-}" ]; then
  export PIERIA_SESSION_ID="$CODEX_SESSION_ID"
fi

# Resolve the transcript path from the first env var Codex provides.
TRANSCRIPT_PATH=""
for _candidate in "${CODEX_TRANSCRIPT_PATH:-}" "${CODEX_SESSION_TRANSCRIPT:-}" "${CODEX_ROLLOUT_PATH:-}"; do
  if [ -n "$_candidate" ] && [ -f "$_candidate" ]; then
    TRANSCRIPT_PATH="$_candidate"
    break
  fi
done

if [ -n "$TRANSCRIPT_PATH" ]; then
  sh "${_HARNESS_DIR}/ingest.sh" "$TRANSCRIPT_PATH"
else
  printf '[pieria/codex-stop] WARNING: no transcript env var set or file not found; skipping ingest.\n' >&2
  printf '[pieria/codex-stop] VERIFY: confirm the Codex Stop hook transcript env var in current docs.\n' >&2
  exit 0
fi

exit 0
