---
description: Pin a memory in Pieria (writes directly to the daemon)
---
Pinning to Pieria memory (deterministic — written directly to the daemon, not model-mediated):

!`sh <PIERIA_HARNESS_DIR>/remember.sh "$ARGUMENTS"`

If the output above is a "usage:" message, no content was given. Don't just report the usage message — instead, review the recent conversation, pick the 1-3 most relevant memories worth persisting (facts, instructions, decisions, events), and store each with the `remember` MCP tool (`mcp__pieria__remember`), choosing an appropriate `type` and `topicKey`. Briefly tell the user what you stored.

<!--
VERIFY against current OpenCode docs (as of 2026-07): the .opencode/command/*.md
command format, $ARGUMENTS substitution, and !`...` shell injection.
-->
