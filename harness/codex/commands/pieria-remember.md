# pieria-remember

If the memory to store below is non-empty, store it in Pieria by calling the `remember`
MCP tool (`mcp__pieria__remember`). Infer a sensible `type` — `fact` (default),
`instruction`, `event`, or `task` — and pass the text as `content`. If the user prefixed
the text with `fact:` / `instruction:` / `event:` / `task:`, use that as the type.

If it is empty, no explicit content was given: instead, review the recent conversation,
pick the 1-3 most relevant memories worth persisting (facts, instructions, decisions,
events), and store each with `mcp__pieria__remember`, choosing an appropriate `type` and
`topicKey`. Briefly tell the user what you stored.

Memory to store:

$ARGUMENTS

<!--
VERIFY against current Codex CLI docs (as of 2026-07): Codex custom prompts are
message templates and cannot execute shell, so this command is model-mediated
(the model calls the MCP tool) rather than a direct daemon write. Confirm the
argument-substitution token ($ARGUMENTS vs $1) for the installed Codex version.
-->
