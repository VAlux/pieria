---
description: Recall relevant memories from Pieria for a query
argument-hint: <query>
allowed-tools: Bash(<PIERIA_BIN>:*)
---

Prior context recalled from Pieria for "$ARGUMENTS":

!`<PIERIA_BIN> hook recall --limit 10 --harness claude-code -- "$ARGUMENTS"`

Use the recalled context above where relevant (verify against current code before relying on it).
