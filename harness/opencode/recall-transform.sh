#!/usr/bin/env sh
# recall-transform.sh — OpenCode experimental.chat.system.transform plugin for Pieria.
#
# OpenCode has no SessionStart hook (issue #14808); the community workaround is
# experimental.chat.system.transform, which lets a plugin augment the system prompt. This script
# passes the original system prompt through unchanged, then appends recalled prior context so the
# agent starts primed. Fail-closed: if the daemon is down it appends nothing and never errors.
#
# VERIFY against current OpenCode docs (as of 2026-07): the experimental.chat.system.transform key,
# the stdin/stdout contract, and whether it fires per-session or per-turn.
#
# Environment variables:
#   PIERIA_DAEMON_URL     — daemon base URL (default: http://127.0.0.1:8077)
#   PIERIA_PROFILE        — explicit profile override (see profile-name.sh)
#   PIERIA_RECALL_QUERY   — override the primer query
#   PIERIA_RECALL_TIMEOUT — recall curl --max-time seconds (default: 8)

set -e

_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
_HARNESS_DIR=$(cd "${_SCRIPT_DIR}/.." && pwd)

# Pass the original system prompt through first.
cat

# Append recalled context via the shared recall client (fail-closed: prints nothing if unavailable).
QUERY="${PIERIA_RECALL_QUERY:-What should I know about this project?}"
RECALL=$(sh "${_HARNESS_DIR}/recall.sh" "$QUERY" 10 2>/dev/null) || true

if [ -n "$RECALL" ]; then
  printf '\n\n---\nPrior project context (Pieria):\n%s\n' "$RECALL"
fi

exit 0
