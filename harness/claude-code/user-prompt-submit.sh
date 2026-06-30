#!/usr/bin/env sh
# user-prompt-submit.sh — Claude Code UserPromptSubmit hook (auto-recall).
#
# Reads the hook JSON on stdin, extracts the user's prompt, and — for non-trivial prompts — fast-recalls
# Pieria for memories relevant to THAT prompt, printing a context block to stdout. Claude Code injects a
# UserPromptSubmit hook's stdout into the model's context for that turn, so this is what surfaces
# task-relevant prior memory without the agent having to call recall itself.
#
# VERIFY against current Claude Code docs: the UserPromptSubmit event, its stdin JSON shape (a "prompt"
# field), and the "stdout is injected as context" behavior may change across releases.
#
# FAIL-CLOSED: any error, a missing python3, or a gated-out prompt exits 0 with no stdout — the turn
# proceeds unaffected.
#
# Environment variables:
#   PIERIA_RECALL_MIN_CHARS — skip prompts shorter than this many chars (default: 24)
#   PIERIA_RECALL_LIMIT     — number of memories to inject (default: 5)
#   (plus everything recall.sh honors: PIERIA_DAEMON_URL, PIERIA_PROFILE, PIERIA_RECALL_TIMEOUT)

set -e

_SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
_HARNESS_DIR=$(cd "${_SCRIPT_DIR}/.." && pwd)

MIN_CHARS="${PIERIA_RECALL_MIN_CHARS:-24}"
LIMIT="${PIERIA_RECALL_LIMIT:-5}"

# python3 parses the stdin hook JSON (and recall.sh needs it downstream too); degrade to no-op without it.
if ! command -v python3 >/dev/null 2>&1; then
  exit 0
fi

# Extract the prompt text from the UserPromptSubmit JSON on stdin.
PROMPT=$(python3 -c 'import sys, json
try:
    print(json.load(sys.stdin).get("prompt", "") or "")
except Exception:
    pass' 2>/dev/null) || exit 0

# Gate: skip empty/short prompts, slash commands, and trivial acknowledgements — they are not worth a
# recall and would only add noise + latency to the turn.
TRIMMED=$(printf '%s' "$PROMPT" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
[ -z "$TRIMMED" ] && exit 0
case "$TRIMMED" in
  /*) exit 0 ;;
esac
LOWER=$(printf '%s' "$TRIMMED" | tr '[:upper:]' '[:lower:]')
case "$LOWER" in
  y | n | yes | no | ok | okay | go | continue | stop) exit 0 ;;
esac
LEN=$(printf '%s' "$TRIMMED" | wc -c | tr -d ' ')
if [ "$LEN" -lt "$MIN_CHARS" ]; then
  exit 0
fi

# Delegate the actual recall + injection to the shared client.
sh "${_HARNESS_DIR}/recall.sh" "$TRIMMED" "$LIMIT"
exit 0
