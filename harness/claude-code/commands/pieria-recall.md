---
description: Recall relevant memories from Pieria for a query
argument-hint: <query>
allowed-tools: Bash(pieria:*)
---

Prior context recalled from Pieria for "$ARGUMENTS":

!`<PIERIA_BIN> hook recall "$ARGUMENTS" --limit 10 --harness claude-code`

Use the recalled context above where relevant (verify against current code before relying on it).
