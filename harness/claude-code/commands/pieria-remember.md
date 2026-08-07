---
description: Pin a memory in Pieria (writes directly to the daemon)
argument-hint: "[fact:|instruction:|event:|task:] [key:<topic-key>] <content>"
allowed-tools: Bash(<PIERIA_BIN>:*), mcp__pieria__remember
---
Pinning to Pieria memory (deterministic — written directly to the daemon, not model-mediated):

!`<PIERIA_BIN> hook remember --harness claude-code -- "$ARGUMENTS"`

Add `key:<topic-key>` (e.g. `fact: key:embedding-dimension the dimension is 768`) for a fact whose value changes over time — a later pin under the same key supersedes the earlier one instead of accumulating a duplicate. The two markers may appear in either order.

If the output above is a "usage:" message, no content was given. Don't just report the usage message — instead, review the recent conversation, pick the 1-3 most relevant memories worth persisting (facts, instructions, decisions, events), and store each with the `remember` MCP tool (`mcp__pieria__remember`), choosing an appropriate `type` and `topicKey`. Briefly tell the user what you stored.
