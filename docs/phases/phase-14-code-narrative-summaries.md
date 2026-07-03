# Phase 14 — Code Narrative Summaries

Status: implemented.

## Motivation

The Phase 13 code index is deterministic and extractive: it answers *what exists* ("Source file X
defines: class A, method b") and — with the first-class edge evidence follow-up — *who touches
what* ("A#b calls C#d"). It cannot answer *what the code is for*: "what does the retrieval module
do", "what is this project's architecture", "where does training happen" have no memory to
retrieve unless a conversation happened to describe it.

Phase 14 adds the interpretive layer: after deterministic indexing, the **synthesis (large) model**
writes summary memories — per-file purpose summaries, per-module roll-ups, and one repository
architecture overview. They are ordinary keyed `fact` memories, so they flow through
FTS/vectorization/retrieval **with zero retrieval changes**. This is the code-facing half of
POTENTIAL_FEATURES #5 (consolidation) and #12 (profile compaction).

## Design

### Granularity (cumulative, configurable)

- `architecture` — one repo-overview memory (topicKey `code:summary:architecture`)
- `module` (default) — per-module summaries (`code:summary:module:<path>`) + the overview
- `file` — per-file summaries (`code:summary:file:<path>`) + modules + overview

Targets are generated children-first (files → modules → architecture) so lower-level summaries
feed the parent prompts: module prompts include the member file summaries when present, the
architecture prompt includes the module summaries (falling back to member-path listings at
`architecture` granularity). Module membership uses the same `ModulePaths` build-marker logic as
the indexer, with a `(root)` pseudo-module for unanchored files.

### Content-addressed skipping and supersession

Each summary memory's payload carries the hash of the code it summarizes
(`{"source":"code-summary","level":…,"path":…,"hash":…}`, see `CodeSummaryPayload`):

- file: `PROMPT_VERSION + ":" + contentHash` (same normalization as `indexOne`)
- module: `Hash.hash128(PROMPT_VERSION, modulePath, sorted "path=contentHash" lines)`
- architecture: `Hash.hash128(PROMPT_VERSION, sorted "modulePath=moduleHash" lines)`

Before summarizing a target, `CodeSummarizationService` looks up the active memory by topicKey
(`findActiveByTopicKey`) and compares the payload hash — equal means **zero model calls** for that
target. When the code changed, storing the new summary supersedes the stale one via the normal
keyed-fact machinery (old embedding removed, new row enqueued for vectorization). The
`PROMPT_VERSION` salt forces full regeneration after prompt changes.

### Execution and failure policy

Summarization runs **inside the existing async index task** (`POST /code/async`), after the
deterministic pass, reporting `"summarize"` progress ticks through the same task. It is
best-effort at every level: a per-target failure is counted and skipped, and the whole stage is
wrapped so indexing results are never affected. The synchronous `POST /code` endpoint stays
model-free by contract and never summarizes.

The model stage is `ModelGateway.summarizeCode(CodeSummaryInput)` — plain prose out, on the
synthesis chat client, with reasoning governed by the `summarizeCode` stage name (synthesis tier
in `Reasoning.enabledFor`).

### Opt-in

Off by default (`pieria.code.summarization.enabled=false`) because it puts the large model in the
indexing path. Per-run override: `CodeIndexRequest.summarize` (nullable Boolean; null follows
config), surfaced as `pieria onboard --source-code --summarize`. Config keys
(`pieria.code.summarization.*`: `enabled`, `granularity`, `max-source-chars-per-file`,
`max-files-per-module-prompt`, `max-modules-in-architecture-prompt`) are process-global
(deliberately not in `DaemonOverrides`).

## Trade-offs / future work

- **Fast-path exclusion**: summaries use `CODE_SESSION`, so the injection/fast recall path filters
  them out like all code-derived memories. The architecture overview would be excellent
  session-start injection material — revisit with POTENTIAL_FEATURES #12.
- **Serialized model calls**: `file` granularity costs one synthesis call per file on first run;
  bounded parallelism (like `maxExtractionConcurrency`) is future work.
- **Identical-prose edge case**: if the model regenerates byte-identical text for changed code, the
  content-addressed memory id collides with the active row and the payload hash is not refreshed,
  so the next run re-summarizes that target. Idempotent and accepted.
- Prompt-size blowup on large repos is mitigated by the three caps; truncation is silent.
