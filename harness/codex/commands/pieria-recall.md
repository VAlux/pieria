# pieria-recall

Recall relevant prior context from Pieria by calling the `recall` MCP tool
(`mcp__pieria__recall`) with the text below as the query, then use the returned
memories to inform your answer (verify against current code before relying on them).

Query:

$ARGUMENTS

<!--
VERIFY against current Codex CLI docs (as of 2026-07): Codex custom prompts are
message templates and cannot execute shell, so this command is model-mediated
(the model calls the MCP tool). Confirm the argument-substitution token
($ARGUMENTS vs $1) for the installed Codex version.
-->
