---
description: Pin a memory in Pieria (writes directly to the daemon)
argument-hint: "[fact:|instruction:|event:|task:] <content>"
allowed-tools: Bash(sh:*)
---
Pinning to Pieria memory (deterministic — written directly to the daemon, not model-mediated):

!`sh <PIERIA_HARNESS_DIR>/remember.sh "$ARGUMENTS"`
