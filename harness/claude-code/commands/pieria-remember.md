---
description: Pin a memory in Pieria (writes directly to the daemon)
argument-hint: "[fact:|instruction:|event:|task:] <content>"
allowed-tools: Bash(pieria:*), mcp__pieria__remember
---
Pinning to Pieria memory (deterministic — written directly to the daemon, not model-mediated):

!`<PIERIA_BIN> hook remember "$ARGUMENTS" --harness claude-code`

If the output above is a "usage:" message, no content was given. Don't just report the usage message — instead, review the recent conversation, pick the 1-3 most relevant memories worth persisting (facts, instructions, decisions, events), and store each with the `remember` MCP tool (`mcp__pieria__remember`), choosing an appropriate `type` and `topicKey`. Briefly tell the user what you stored.
