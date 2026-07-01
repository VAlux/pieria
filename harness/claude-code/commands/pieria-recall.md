---
description: Recall relevant memories from Pieria for a query
argument-hint: <query>
allowed-tools: Bash(sh:*)
---
Prior context recalled from Pieria for "$ARGUMENTS":

!`sh <PIERIA_HARNESS_DIR>/recall.sh "$ARGUMENTS" 10`

Use the recalled context above where relevant (verify against current code before relying on it).
