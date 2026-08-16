# Reducing model interactions in the ingest → recall pipeline

Where Pieria calls a model today, which of those calls can be replaced by deterministic Java or
removed outright, and what each change costs in quality. The organizing goal is **fewer model
interactions**, not fewer tokens — a local single-GPU Ollama is round-trip bound, so collapsing
N calls into 1 is worth more than shrinking each prompt.

Pieria has **two independent cost axes**, and it is worth keeping them apart:

- **Local inference** — extraction, verification, graph, query analysis, synthesis. Runs on the
  configured provider (Ollama by default). Costs latency and GPU. This is Tiers 0–3 below.
- **Harness context** — what Pieria parks in the *agent's* context window: MCP tool schemas,
  hook injections, policy blocks, and tool results. Costs the user's Claude Code / API quota, and
  Pieria never sees these tokens. This is [Part 2](#part-2--harness-context-footprint).

Optimizing one does nothing for the other. A change that removes twenty local extraction calls
does not reduce the agent's token bill by a single token.

Items are grouped by how much evidence you need before shipping them. Tier 0 and Tier 1 change
*what work is issued*, never *what the model is asked*, so their outputs are identical by
construction and they need no benchmark. Tier 2 and Tier 3 change model inputs and must be
measured against a committed LoCoMo baseline first (see [Measurement prerequisite](#measurement-prerequisite)).

## Where the calls are today

| Stage | Call | Volume | Model tier |
|---|---|---|---|
| Ingest | `extractUnified` (`IngestionService.java:722`) | chunks × `extractionSamples` | small |
| Ingest | `verifyAll` (`IngestionService.java:459`) | 1 per chunk with suspects | small |
| Ingest | `extractGraphAll` (`IngestionService.java:541`) | 1 per chunk with survivors | small |
| Ingest | `classify` (`IngestionService.java:637`) | 1 per `CORRECT` verdict | small |
| Ingest | `embed` (`VectorizationWorker.java:137`) | 1 per stored memory | embedding |
| Recall | `analyzeQuery` (`RetrievalService.java:393`) | 1 per recall at `ANALYZED`+ | small |
| Recall | `embed` (`RetrievalService.java:410`) | 1 for the query, 1 for HyDE | embedding |
| Recall | `synthesizeRecall` (`RetrievalService.java:384`) | 1 per recall at `SYNTHESIZED` | **large** |
| Background | `extractGraphAll` (`ReminiscenceService.java:148`) | 1 per batch of 8 orphans | small |
| Background | `summarizeCode` (`CodeSummarizationService.java:208`) | per file/module/repo, opt-in | **large** |

Ingest dominates, and inside ingest the amplification described in Tier 0 dominates everything else.

---

# Tier 0 — The re-ingest amplification

## The mechanism

`CcStopCommand` fires on **every assistant turn** ("capture the transcript at the end of a turn"),
and `TranscriptIngestor.ingestFile` reads and POSTs the **entire transcript file** each time.
`ProfileController.ingestTranscript` (`:141`) then parses every message and
`IngestionService.ingestDetailed` re-chunks and re-extracts from message 0.

Storage is idempotent — `SqliteMemoryStore.insertMessages` (`:443`) is `INSERT OR IGNORE` over
content-addressed ids — but inference is not. The `inserted == 0` signal at
`SqliteMemoryStore.java:457` already says "this message was seen before" and is currently discarded.

Note that all three ingest hooks (`Stop`, `PreCompact`, `SessionEnd`) share one body —
`AbstractIngestHookCommand` calls `TranscriptIngestor.ingestFile` and nothing else. They capture
identical content and differ only in *when* they fire. Stop is therefore a **durability**
mechanism, not a quality one: it exists so a crashed session does not lose its memories.

## Sizing it

`ClaudeCodeTranscriptParser` keeps only `user`/`assistant` **text** blocks — `thinking`,
`tool_use`, `tool_result`, meta turns, and sidechain (subagent) turns are all dropped (`:61`,
`:86`). Surviving volume is therefore a small fraction of the raw JSONL: call it `c ≈ 1,500` chars
of dialogue per turn against the 10,000-char `chunk-size-chars` default.

For a session of `T` turns, final chunk count is `F = T·c/S`, while today's cost is
`Σ_{t=1..T} ceil(t·c/S) ≈ T·F/2`:

| Session | Final chunks (ideal) | Extractions today | Amplification |
|---|---:|---:|---:|
| 40 turns | 6 | ~120 | ~20× |
| 100 turns | 15 | ~750 | ~50× |

Each of those also drags a `verifyAll` and an `extractGraphAll` behind it. The invariant to
remember is that **amplification is `T/2`** — it is not a fixed overhead, it grows with session
length, so the longest and most valuable sessions are punished hardest.

---

**1. Chunk-extraction ledger (skip already-extracted chunks)**
Saves: ~120 → ~40 extractions on a 40-turn session | Risk: none | Status: **implemented**

Reuses the existing `ingest_ledger` table under a new {@code chunk} scope — no migration. Keyed
`sessionId#chunkIndex` → `Hash.hash128(pipeline version, samples, the three prompt-shaping tuning
knobs, chunk transcript)`. Fails open: a store without ledger support just extracts everything.
Kill switch: `pieria.ingestion.chunk-ledger-enabled`.

`Chunker.chunk` (`:57`) fills greedily from index 0, so appending messages **never moves an earlier
chunk boundary**. Every completed chunk is byte-identical across re-ingests; only the trailing
partial chunk changes. A ledger keyed on
`SHA-256(chunk transcript + extraction model + prompt version)` therefore hits ~100% on the
stable prefix.

Design notes:
- On a hit, skip extraction *and* the chunk's verify and graph calls — the survivors were already
  stored, and storage is content-addressed, so replaying them is a no-op.
- Include the prompt version and model name in the hash so a prompt edit or model swap invalidates
  cleanly rather than silently serving stale extractions.
- `TranscriptNormalizer.resolveRelativeDates` rewrites "today"/"yesterday" and calendar periods
  ("next month") against the turn's own timestamp, falling back to the request timestamp, so
  an untimestamped chunk's hash legitimately changes across a day boundary. That is correct behavior
  — different content — and only costs one cold miss.

**2. Defer the trailing partial chunk**
Saves: ~40 → ~7 extractions on a 40-turn session | Risk: low | Status: **implemented**

A `partial` query parameter on `/ingest/transcript` (default false, so an older CLI still flushes)
selects `ChunkLedgerMode.DEFER_TRAILING`. The Claude Code and Codex end-of-turn hooks send it;
pre-compact, session-end, and the OpenCode compaction hook do not.

The ledger alone does not finish the job. The trailing chunk grows by one turn's text on every
Stop, so its hash differs every time and it **misses the cache on every single turn** — leaving
roughly one extraction per turn (~40) against an ideal of ~7.

Extract only *closed* chunks. Let the trailing partial chunk wait until a boundary closes it, or
until `PreCompact`/`SessionEnd` forces a final flush. Stop then issues **zero model calls on ~4 of
every 5 turns**, costing a file read, a parse, and a few hash lookups.

Why the durability cost is smaller than it looks:
- `store.insertMessages` runs *before* extraction and is model-free, so **raw messages are still
  persisted every turn**. Only distillation lags, never capture.
- `MessageFtsChannel` retrieves against `messages_fts` as a live channel (`weight-fts-message`,
  default 0.5), so not-yet-extracted content is less *distilled*, not invisible.
- The exposure is therefore at most one partial chunk of un-extracted memories (~10k chars), and
  only in the crash case — a clean exit flushes via `SessionEnd`.

Related gap worth closing regardless: **there is no transcript backfill path.** If `SessionEnd`
never fires (SIGKILL, crash, machine death), that session is lost permanently even though its
JSONL is still on disk. A `pieria ingest --transcript <path>` command would close that hole, and
is the prerequisite for ever considering dropping the Stop hook outright.

---

# Tier 1 — Free wins (output-identical, no baseline needed)

**3. Batch the embedding calls**
Saves: 32 → 1 embedding round trips per outbox drain | Risk: none | Status: proposed

`OpenAiModelGateway.embed` (`:1151`) calls `embeddingModel.embedForResponse(List.of(text))` — a
singleton list. `VectorizationWorker.drainOnce` (`:62`) drains `outbox-batch-size` (default 32)
rows and then fans out 32 separate single-item HTTP calls on virtual threads.

Add `ModelGateway.embedAll(List<String>)` and hand the whole batch to one call. The Spring AI
surface is already list-shaped, so this is a call-site change plus a batch-aware
`completeVectorization`. Same model, same inputs, same vectors — the stored embeddings are
byte-identical.

**4. Don't synthesize over empty evidence**
Saves: 1 large-model call per zero-hit recall | Risk: none | Status: proposed

`RetrievalService.java:250-251` calls `synthesizeRecall` whenever `mode.synthesizes()`, with no
check that `fused` is non-empty. A recall that finds nothing still burns a **large-model** call to
produce "I don't have memory evidence for that." Return a canned string when `fused.isEmpty()` and
skip both the temporal pass and synthesis.

This fires more than it looks like it should: recall against a fresh or narrowly-scoped profile,
and any query whose terms miss every channel.

**5. Content-addressed embedding cache**
Saves: every repeat embedding, forever | Risk: none | Status: proposed

Key `SHA-256(text + embedding model + dimension) → vector` in SQLite. Embeddings are deterministic,
so this is pure dedup with no semantic effect.

On ingest, re-stored or superseded-then-recreated memory text stops re-embedding.

(This item used to claim a second payoff: the session-open primer ran a fixed constant query on
every session start, so its embedding only ever needed computing once. That primer was removed in
favour of a pointer — see #15 — so the ingest-side dedup is now the whole case for this item. It
still stands on that alone.)

---

# Tier 2 — Deterministic substitutions (baseline required)

**6. Turn on `graph-from-extraction`**
Saves: 1 call per chunk (~⅓ of ingest chat calls) | Risk: low | Status: implemented, default off

The knob already exists: `pieria.ingestion.graph-from-extraction` (`PieriaProperties.java:224`,
default `false`), wired through `OpenAiModelGateway.graphInstruction()` (`:648`) and consumed at
`:249`. When on, unified extraction emits `graphEntities`/`graphTriples` inline and
`IngestionService` skips `extractGraphAll` for those survivors (`:558`).

This is a flag flip plus a LoCoMo run. The open question is whether one call doing both jobs
degrades either — measure extraction precision/recall and graph edge counts, not just faithfulness.

**7. Widen the grounding auto-pass with stemming**
Saves: a share of the per-chunk `verifyAll` calls | Risk: low | Status: proposed

`GroundingFilter` has two rules with very different value:

- The **critical-token** rule (tokens containing a digit or `/`) is what actually catches fabricated
  versions, prices, dates, and paths. Keep it exactly as strict as it is.
- The **word-overlap** rule (`MIN_WORD_OVERLAP = 0.6` over raw lowercased tokens, `:32`) is what
  routes legitimate paraphrase to the model verifier — and extraction is *supposed* to paraphrase
  turns into terse declaratives, so surface overlap systematically understates grounding.

Porter-stem and stopword-filter both sides before computing overlap. The stemmer is already the
project's tokenizer of record — every FTS table uses `tokenize='porter'`
(`V1__baseline.sql:93`) — so this stays consistent with retrieval. Net effect: a higher auto-pass
rate at unchanged hallucination protection.

**8. Skip the reclassify on `CORRECT`**
Saves: 1 call per corrected candidate | Risk: low | Status: proposed

`IngestionService.reclassify` (`:635`) re-runs `classify` because "the corrected content invalidates
the original enrichment." In practice a correction fixes a *value*, not the subject — and the method
already falls back to the stale classification when the model fails, so that path is accepted as
adequate. Make it the default: keep the original classification unless a deterministic diff shows
the topic-key-bearing tokens actually changed.

Low volume, but it removes a whole call class from the hot loop.

**9. Route query analysis deterministically**
Saves: 1 call per keyword-shaped recall | Risk: medium | Status: proposed

`analyzeQuery` (`OpenAiModelGateway.java:954`) produces four things; `DeterministicQueryAnalyzer`
already produces three of them (topic keys, FTS terms, entities) and only `hydeStatement` genuinely
requires generation.

For keyword-shaped queries — named entities, file paths, identifiers, single technical terms — the
deterministic analyzer is arguably *better*, because the model's contribution is synonym guessing by
a 9B model. A grounded alternative: expand terms by trigram-matching against the profile's own
`entities` table via `EntityNormalizer.normalizeName`, which is the actual vocabulary in the store.

Route on query shape: keyword/identifier queries take the deterministic path, conceptual or
paraphrased queries keep the model call.

**10. Deterministic graph skeleton**
Saves: a share of the `ReminiscenceService` background volume | Risk: medium | Status: proposed

Topic keys are already normalized dotted subject keys (`user.editor`, `db.engine`) — their segments
are free entity candidates requiring no model call. The code indexer already sets the precedent:
code-derived memories are excluded from reminiscence because "their graph is projected
deterministically from the parse."

Project a skeleton graph from topic keys plus matches against the existing `entities` table, and
send only the memories that yield nothing deterministically to `extractGraphAll`. Relations are the
hard part and should stay model-extracted.

---

# Tier 3 — Measure before touching

**11. The HyDE channel may be redundant**
Saves: 1 chat call + 1 embedding call per recall | Risk: high | Status: needs measurement

`IngestionService.buildEmbedText` (`:813`) prepends each memory's interrogative queries to its
content before embedding. That is HyDE solved in reverse — bridging declarative storage and
interrogative queries **once per memory at write time**, instead of once per query at read time.
The direct-vector channel is therefore already searching question-shaped text.

If `HydeVectorChannel`'s unique contribution to fused top-K is small, dropping it removes both a
chat call and an embedding call from every `ANALYZED`/`SYNTHESIZED` recall, and makes item 9
strictly simpler. Measure its marginal contribution — RRF weights are per-profile
(`weight-hyde-vector`), so this can be A/B'd without a code change.

**12. Deterministic chunk triage**
Saves: extraction calls on no-signal chunks | Risk: high | Status: speculative

Chunks that are pure tool output, diffs, or greetings still cost a full extraction call. A
deterministic "is this worth extracting?" gate would skip them, but a false negative silently loses
a memory and there is no signal that it happened. Do this last, if at all, and only with extraction
recall measured on the hand fixtures.

---

# Measurement prerequisite

Tier 0 and Tier 1 need no benchmark: they change which calls are issued, never what the model is
asked or what it returns.

Everything in Tier 2 and Tier 3 changes model inputs and cannot claim "no quality loss" without a
reference. `docs/eval/BASELINE.md` currently holds a single `_tbd_` row — **there is no committed
baseline to regress against**. Recording one is the gating task for items 6–12:

```bash
./gradlew :eval:locomo --args="--runs=3"
```

Pin and record everything `BASELINE.md` lists (dataset slice, all three models, RRF weights,
ingestion config, judge model, git SHA), then commit the results row. Read faithfulness as the
north star and the diagnostics to localize any regression: the per-category breakdown for items
6–8, hit-rate and MRR for items 9–11.

---

# Part 2 — Harness context footprint

Everything above reduces calls to Pieria's *own* provider. This part is about the tokens Pieria
spends out of the **agent's** budget — the ones that show up in a Claude Code usage breakdown.
Pieria never sees them, never meters them, and none of Tiers 0–3 affect them.

Measured on this repo with the standard `pieria harness install claude-code` layout
(character counts are exact; token figures assume ~3.7 chars/token):

| Component | chars | ~tokens | Source |
|---|---:|---:|---|
| MCP tool schemas (`recall`/`remember`/`list`/`forget`) | 3,651 | 990 | `MemoryTools.java:33,63,85,95` |
| Pieria policy block in the user's `CLAUDE.md` | 3,608 | 975 | user-level config, not in this repo |
| SessionStart hook injection (10 memories) | 2,214 | 600 | `HarnessHookSpec.java:28,32` |
| Dogfooding block in `AGENTS.md` | 568 | 154 | this repo |
| **Fixed per-request prefix** | **10,041** | **~2,700** | |

## Why the prefix is the whole story

All four components sit in front of the conversation, so they are re-sent on **every** API request
of a session, not once per session:

| Requests in session | Prefix tokens re-sent |
|---:|---:|
| 50 | ~136k |
| 150 | ~407k |
| 400 | ~1.09M |

Prompt caching discounts cache reads, but they still count against the user's quota. Against a
typical ~17k-token non-conversation prefix, ~2,700 tokens is ~16% — which matches an observed
16% attribution in the Claude Code usage tab.

The practical consequence: **a token trimmed from the prefix is worth roughly one token per
request for the rest of the session.** A token trimmed from a tool result is worth one token per
*subsequent* request. Nothing else in Pieria has that multiplier.

## Recall results are permanent, and charged twice

`MemoryTools.recall` (`:50`) returns `client.toJson(client.recall(...))` — the entire
`RecallResponse`: the synthesized `answer` **and** the full `memories` list, each carrying
`payload`, `sessionId`, `createdAt`, and `superseded`.

In `SYNTHESIZED` mode that defeats the purpose of the tier. The answer exists precisely so the
agent does not have to read the evidence; returning both means paying for the source material and
its summary. And a tool result is not a transient cost — it stays in the transcript, so a recall at
turn 5 of a 100-request session is re-sent ~95 more times.

**This also makes the impact panel wrong.** `RetrievalService.recordUsage` (`:291`) computes
`servedTokens = Tokens.estimate(answer)` when an answer exists, but the wire ships answer +
memories. Pieria is reporting a compression ratio the caller never actually receives.

## Where to cut, by value

**13. Trim the `CLAUDE.md` Pieria policy block**
Saves: up to ~975 tokens/request | Risk: none (prose edit) | Status: proposed

The single largest lever, nearly as big as all four tool schemas combined, and it is hand-written
prose that can be edited freely. Much of it restates mechanics the tool descriptions already carry
(type mapping, `topicKey` supersession, tier selection). The policy should say *when* to recall and
*what* to remember; the tool schemas should own *how*. Say each thing once.

**14. Return either the answer or the evidence, not both**
Saves: ~1–4k tokens per synthesized recall, permanently | Risk: low | Status: proposed

In `SYNTHESIZED` mode return the answer plus bare memory ids. In `EVIDENCE`/`ANALYZED` return the
memories (there is no answer). Drop `payload`, `superseded`, and `sessionId` from the model-facing
projection in every mode — the agent cannot act on them. Fix `recordUsage` in the same change so
the impact numbers describe what is actually served.

**15. Replace the session primer with a pointer**
Saves: ~500 tokens/session, every session, every harness | Risk: low | Status: **done (2026-08-05)**

The primer ran a fixed recall (`PRIMER_QUERY`, `primerLimit` 10) at every session start. Two rounds
of tuning taught us the query was not the problem.

*Round one* — the original wording ("key facts, active tasks, and recent decisions") was a
near-perfect lexical match for memories describing *the memory system*, because Pieria's own
standing instructions are written in exactly that vocabulary. On `aieep` it returned ten such
memories and nothing about the project at all.

*Round two* — retargeting to codebase vocabulary (architecture, module responsibilities, build/test
commands, conventions, pitfalls) measurably fixed *selection*: `aieep` went from 10 self-referential
memories to 10 distinct project-relevant ones. But on `pieria` it still returned 4/10 restatements
of the module layout and 3/10 memories about the primer machinery itself — because on a memory
system, "architecture of this codebase" and "the memory system" are the same subject. A banned-words
list cannot separate them.

*The resolution* — the categories the query asked for (architecture, module responsibilities, build
commands, conventions) are exactly what `AGENTS.md` already carries into every session. The primer
was duplicating an instruction file, and any better-sourced replacement would duplicate it more
accurately rather than less. Session start also fires at the moment of *minimum* information: it
must guess the subject before the user has spoken, while `recall` fires once the task is known.

Session start now emits a ~45-token pointer (`MemoryPointer`) — count, freshness, and what memory
holds that the repo's files do not — and the content path moved to pull-based `recall`. This closes
the "still open: the limit" question and supersedes the Phase 15 standing-summary follow-up.

**16. Tighten the MCP tool schemas**
Saves: up to a few hundred tokens/request | Risk: low | Status: proposed

The `mode` parameter description alone is ~90 tokens on every request. Compress the descriptions to
the decision-relevant minimum, and reconsider whether `list` and `forget` need to be model-facing at
all — each costs ~100–150 tokens on every request forever, whether or not it is ever called. Both
have CLI equivalents.

## Not applicable here

The measurement prerequisite above does **not** gate this part. Items 13–16 change what the agent
reads, not what Pieria's models are asked, so LoCoMo says nothing about them. Judge item 15 by
whether injected memories are actually relevant, and items 13/14/16 by whether the agent still uses
the tools correctly.
