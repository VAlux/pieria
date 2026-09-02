# Retrieval Reranking Stage — Design

- **Date:** 2026-09-02
- **Feature:** POTENTIAL_FEATURES #3, Phase 9
- **Status:** design approved, implementation plan pending
- **Relationship to `docs/phases/phase-9-retrieval-reranking-stage.md`:** that document remains the
  phase's charter — objective, scope boundary, and acceptance criteria still hold. This design
  supersedes its Implementation Sequence steps 3, 4, and 8 where the two disagree, because it was
  written on 2026-06-02 against a codebase that had no recall tiers, no near-duplicate collapse, no
  code-derived memories, and no console configuration UI.

---

## 1. Objective

Insert a reranking stage between weighted Reciprocal Rank Fusion and the `limit` truncation in the
read pipeline. RRF produces a candidate list optimized for recall; the reranker re-scores that list
against the query and hands a tighter, higher-precision top-K to whatever consumes it — the large
synthesis model at `SYNTHESIZED`, or the agent's own context at `EVIDENCE`.

The stage never fails a recall. Every failure mode degrades to the RRF ordering the pipeline
produces today.

---

## 2. What changed since the phase doc

Four things landed between 2026-06-02 and now that move this design away from the original sequence:

1. **`RecallMode` tiers exist, and `EVIDENCE` is the MCP default** (commit `5ab6e49`). Its documented
   contract is ~1-3s with the query embedding as the only model call. The phase doc assumes one
   uniform pipeline and would have put a small-model call on that path. See D1.
2. **Near-duplicate collapse runs inside `fuse()`.** `RetrievalService.collapseNearDuplicates`
   already performs a `store.embeddingsFor(profileId, ids)` read for its semantic half. The rerank
   stage reuses that read rather than adding one. See D3.
3. **The small tier is a local ~4-8B model.** The phase doc's pointwise 0-10 relevance scores with
   `replace`/`blend` combination modes assume a calibration that model class does not have. See D2.
4. **The console configuration UI exists** (`docs/superpowers/plans/2026-08-26-console-configuration-ui.md`),
   driven by `config/config-schema.json`. New properties are expected to surface there. See D7.

---

## 3. Decisions

### D1 — Two reranker sub-stages, split by tier

A deterministic re-scorer runs in **every** tier; the model-backed reranker runs only when
`mode.usesModelAnalysis()` is true (`ANALYZED`, `SYNTHESIZED`).

```
EVIDENCE    : RRF → collapse → window → semantic re-score →                 → limit
ANALYZED    : RRF → collapse → window → semantic re-score → model rerank    → limit
SYNTHESIZED : RRF → collapse → window → semantic re-score → model rerank    → limit → temporal → synthesis
```

*Rejected: model rerank at `ANALYZED`+ only (the phase doc's implicit position).* It leaves the
default and dominant recall path — the one whose results land directly in an agent's context with no
synthesis to filter them — with no precision improvement at all.

*Rejected: model rerank in every tier.* It roughly doubles `EVIDENCE` latency and breaks a contract
stated in `AGENTS.md`, the tool descriptions, and the user's own global instructions. Precision is
not worth silently making the cheapest tier not-cheap.

*Consequence:* two implementations to maintain and test rather than one. Accepted, because the
deterministic half is a pure function over data the pipeline already holds.

### D2 — The model expresses a coarse label, not a score

`ModelGateway` gains `rerankCandidates(String query, List<String> contents)` returning
`List<RerankLabel>` where `RerankLabel` is `{ ESSENTIAL, RELATED, IRRELEVANT }`.

Candidates are ordered `ESSENTIAL` block, then `RELATED` block, each preserving the incoming
re-scored order. `IRRELEVANT` is dropped (subject to D4).

*Rejected: pointwise 0-10 scores replacing the RRF score (`replace` mode).* A small local model
clusters numeric relevance judgements — nearly everything lands on 7 or 8 — so the resulting order is
mostly noise, and adopting that score discards the channel evidence RRF spent five channels earning.

*Rejected: normalized blend of rerank and RRF scores (`blend` mode).* It needs cross-scale
normalization the phase doc itself flags as unvalidated, and adds two knobs to tune blind. The label
scheme gets the same effect — model judgement modulating, not replacing, channel evidence — with no
normalization and no mix weight.

*Rejected: listwise ordering (the model returns ids in its preferred order).* Uses more of the
model's signal, but a small model ordering 20+ items in one response is noisy, and a partially
hallucinated id list is far harder to validate than a per-line label.

*Consequence:* `RecallCandidate.score` keeps carrying the RRF score; the rerank verdict is expressed
in list order and reported in debug diagnostics. The returned list is therefore no longer sorted by
`score`. Verified safe: nothing downstream sorts by it — the gateway module never sees a score,
`recordUsage` is order-independent, and synthesis, the injection path, and the MCP rendering all use
list order. This must be stated in the `RecallCandidate` javadoc.

### D3 — The stage lives inside `fuse()`, after collapse, before truncation

`RetrievalService.fuse(...)` currently runs `fusion.fuse(hits)` → `collapseNearDuplicates(...)` →
truncate to `limit`. The truncation moves to the end:

```
fused    = fusion.fuse(hits)
vectors  = store.embeddingsFor(profileId, ids)      // hoisted out of collapse, read once
distinct = collapseNearDuplicates(fused, vectors)
window   = distinct.subList(0, min(rerankWindow, distinct.size()))
rescored = semanticRescorer.rescore(query, queryEmbedding, window, vectors)
reranked = modelReranker.rerank(query, rescored)    // ANALYZED+ only
result   = reranked.subList(0, min(limit, reranked.size()))
```

Moving truncation to the end is what allows a candidate ranked below `limit` by RRF to be promoted
into the answer. That promotion is the entire point of the stage.

The embedding lookup is hoisted out of `collapseNearDuplicates` into `fuse()` and passed to both
consumers. Today it is fetched only when `semanticDuplicateThreshold > 0`; it must now also be
fetched when `rerankSemanticWeight > 0`. Zero extra I/O when either was already on.

*Rejected: a separate stage after `fuse()` in `recall(...)`.* It would need its own second
`embeddingsFor` read for the same candidate ids, and would sit awkwardly after a truncation it wants
to widen.

### D4 — Drop, but treat unanimous irrelevance as no signal

`IRRELEVANT` candidates are dropped normally. If **every** candidate in the window is labelled
`IRRELEVANT`, the stage logs a warning and returns its input unchanged.

Pieria already treats abstention as correct behaviour (`ModelGateway.AnswerVerdict.ABSTAINED`), so a
genuinely empty evidence list is defensible in principle. But a flaky small-model batch that labels
everything irrelevant is indistinguishable from that verdict at the call site, and would silently
blank a recall RRF had right — a failure with no signal that it happened. Unanimity is the cheap
discriminator: a query with a real answer in the window will not produce it, and a broken response
usually will.

*Rejected: honour drops unconditionally.* Silent blanking, no way to tell model failure from truth.

*Rejected: reorder only, never drop.* Safest, but leaves irrelevant candidates padding the context
whenever fewer than `limit` results are actually relevant — which is precisely the waste this feature
exists to remove.

### D5 — The deterministic re-scorer blends cosine into the RRF score

```
score = (1 − w)·normalize(rrf) + w·max(0, cosine(queryEmbedding, memoryVector))
```

RRF scores are min-max normalized **within the window** so both terms live in `[0,1]`. `w` is
`rerankSemanticWeight` (default `0.4`); `w = 0` short-circuits the sub-stage entirely.

This gives FTS, exact-key, and graph hits a semantic score they never had, so candidates from
channels with incomparable native scales compete on one axis before the model ever sees them.

**A candidate with no stored vector keeps its normalized RRF score rather than scoring 0.** `task`
memories are deliberately never embedded, and a freshly ingested memory may still be sitting in the
vectorization outbox. Neither is evidence of irrelevance, and penalising them would make recall
quality a function of indexer backlog.

Ties break on the existing RRF tie-break (recency by `MemoryTimes.knowledgeTime`, then memory id), so
the stage is fully deterministic for a fixed input.

No query embedding — vector search disabled, or the embed call failed — means pass-through. This is
the same degradation the vector channels already take.

*Rejected: MMR diversification.* Genuinely useful for the 0.5-0.78 cosine band that near-duplicate
collapse deliberately leaves alone, but it adds a second knob entangled with both collapse
thresholds, and those interactions need tuning together. Deferred until eval justifies it.

*Rejected: MMR-only with RRF as the relevance term.* Preserves today's ordering exactly, but leaves
keyword-only hits with no semantic score, which is the main defect this sub-stage fixes.

### D6 — Failure is always pass-through, and the model call is bounded off-thread

The model rerank runs on a virtual thread bounded by `Future.get(rerankTimeoutMs)`, matching the
posture `runChannels` already uses for best-effort channels.

Every one of these degrades to the incoming order, logged at WARN, never propagated:

| condition | handling |
|---|---|
| gateway returns empty list | no signal — pass through |
| returned size ≠ `contents.size()` | **failure** — pass through |
| model unreachable / throws | pass through |
| timeout | pass through |
| all labels `IRRELEVANT` | pass through (D4) |
| no query embedding | re-scorer passes through (D5) |

Misalignment is treated as failure rather than as partial data for the same reason `embedAll` must
throw on a short list: a label attached to the wrong candidate is a silent wrong drop with nothing to
signal it happened.

Unparseable or missing individual lines degrade in the safe direction — they become `RELATED`, so a
garbled response reorders nothing and drops nothing.

**Threading wrinkle to encode in the implementation:** `InferenceUsageSink` is thread-bound and
virtual threads do not inherit thread-locals, so the recall's `InferenceUsageAccumulator` must be
re-bound *inside* the rerank worker — exactly what ingestion does at its `bounded(...)` choke point.
A rerank that times out may still land its tokens after `recordInferenceUsage` has run; the
accumulator is `LongAdder`-striped so that is safe, it simply means a timed-out rerank's tokens can
be missed from that recall's spend line. Acceptable, and documented in the code.

The stage label is `"rerank"`, which falls through `InferenceTier.forStage`'s default branch to
`EXTRACTION` — correct, and needs no change there.

### D7 — Six flat `rerank*` properties, editable at both scopes

Flat fields on `PieriaProperties.Retrieval` with a `rerank` prefix, following the existing
`codeGraph*` precedent rather than introducing a nested block the TOML codec and config schema would
have to learn.

| key | default | meaning |
|---|---|---|
| `rerank-enabled` | `true` | master switch; off ⇒ today's behaviour exactly |
| `rerank-semantic-weight` | `0.4` | `w` in D5; `0.0` disables the deterministic sub-stage |
| `rerank-model-enabled` | `true` | model rerank at `ANALYZED`+ |
| `rerank-window` | `30` | candidates the stage considers |
| `rerank-snippet-chars` | `400` | per-candidate text bound in the prompt |
| `rerank-timeout-ms` | `4000` | bound on the model call |

**Naming constraint:** the blend knob is `rerank-semantic-weight`, *not* `weight-rerank-semantic`.
`channel-mix.js` selects fusion channels with `f.key.indexOf("retrieval.weight-") === 0`; a
`weight-`-prefixed key would appear in the channel mix bar and its legend as though it were a
retrieval channel, which it is not.

*Rejected: a nested `Rerank` record under `Retrieval`.* Cleaner in Java, but `[pieria.retrieval]` in
the TOML is flat, `config-schema.json` keys are two-segment, and both `DaemonOverrides` and
`EffectiveConfigResolver.overlayRetrieval` would need to learn a third level. Not worth it for six
fields.

*Rejected: a separate `RerankProperties` class (the `TraceProperties` pattern).* Top-level properties
classes are not per-profile overridable, and per-profile rerank tuning is wanted.

---

## 4. Components

New package `dev.alvo.pieria.retrieval.rerank`:

| type | responsibility | depends on |
|---|---|---|
| `Reranker` | the seam: `List<RecallCandidate> rerank(RerankInput)`. Never throws, never widens the input list. | — |
| `SemanticRescorer` | `Reranker` impl. D5. Pure function over candidates + vectors + query embedding. No I/O, no model. | — |
| `ModelReranker` | `Reranker` impl. D2/D4/D6. Windowing, prompt assembly, label mapping, bucketing, degradation. | `ModelGateway` |

Both are `Reranker` implementations, composed in that order by `RetrievalService.fuse(...)` — the
re-scorer's output is the model reranker's input. `rerank-enabled=false` skips **both**; it is a
master switch, not a switch on the model half only (`rerank-model-enabled` is that switch).
| `RerankInput` | `(query, queryEmbedding, candidates, vectors, config)` | — |
| `RerankDiagnostics` | `(input, output, dropped, latencyMs, fellBack, stage)` | — |

`RerankLabel` goes in `dev.alvo.pieria.retrieval.model` alongside `QueryAnalysis` and
`RecallCandidate`, which `ModelGateway` already imports.

The `Reranker` seam is where a dedicated reranker model (bge-reranker via Ollama, or a Spring AI
reranking abstraction) would later land as a third implementation. Not built now.

### Gateway contract

```java
/**
 * Rerank: label each candidate's relevance to the query. Runs on the small/fast model tier.
 * Additive and degradable — the default returns an empty list ("no signal"), so stubs and
 * gateways without rerank support keep working. A non-empty result MUST be aligned 1:1 with
 * {@code contents}; callers treat any other size as failure.
 */
default List<RerankLabel> rerankCandidates(String query, List<String> contents) {
  return List.of();
}
```

`OpenAiModelGateway` implements it via `callExtractionText` + a new `prompts/rerank-candidates.txt`
using a compact line protocol (`1 essential`), following `extract-graph-batch` rather than structured
JSON — cheaper and markedly more robust on a small local model.

---

## 5. Diagnostics

`RetrievalDiagnostics` gains a `RerankDiagnostics` component, surfaced through
`RecallResponse.RecallDebug` under the existing `debug=true` request flag. Default responses stay
concise; no change to the non-debug wire shape.

`rerankMs` joins the existing `recall latency` INFO log line alongside `fusionMs` and `synthesisMs`.

Channel provenance (`RecallCandidate.source`) survives the stage untouched.

---

## 6. Console configuration

`config-schema.json` is the single source of truth for both console pages, and the two scopes are
currently disjoint: 28 `scope: profile, tier: live` keys render on the per-profile page, 30
`scope: global, tier: restart|locked` keys render on the global page. **No `pieria.retrieval.*` key
is editable from the global page today.**

Each rerank knob therefore gets two schema entries:

| scope | key form | tier | section |
|---|---|---|---|
| `global` | `pieria.retrieval.rerank-*` | `restart` | `rerank` |
| `profile` | `retrieval.rerank-*` | `live` | `rerank` |

`restart` is the honest tier for the global entries: the daemon binds `pieria.properties` once at
startup via `spring.config.import` and never re-reads it.

No new backend code is required for either page. `GlobalConfigService.effective()` reads through
`Environment.getProperty(field.key())` and writes through `PropertiesFileEditor`, so any
Spring-bound property becomes editable once it is in the schema. `ProfileConfigController.WHITELIST`
is derived reflectively from `DaemonOverrides.Retrieval.class`, so adding the six fields there
extends the whitelist automatically — while `ConfigRecordDriftTests` will fail until
`DaemonOverrides.isEmpty()` lists them, which is the drift guard working as designed.

This makes rerank the first retrieval family editable on the global page. Lifting the other 28
profile-scoped keys (22 `retrieval.*`, 6 `ingestion.*`) onto that page is **out of scope** —
unrelated to Phase 9, and its own change.

Front-end edits, all small:

- `profile.js` — add `rerank: "Reranking"` to `SECTION_TITLES`; leave collapsed by default, like
  `graph`, `code-graph`, and `fusion`.
- `profile.js` — generalize the section-inactive rule. It is currently a single `graphOff` boolean
  threaded into `renderSection`, greying out graph traversal fields when `weight-graph` is 0. The
  same applies to rerank: with `rerank-enabled` off, the other five fields are inert. Replace the
  boolean with a small per-section predicate rather than adding a second parallel flag — a targeted
  cleanup of code this change already touches, not a refactor.
- `global.js` — the header comment counting "27 restart-tier keys … seven functional groups" becomes
  33 and eight. Grouping is data-driven; this is the only edit.
- `channel-mix.js` — **no change**, guaranteed by the D7 naming constraint.

`pieria-default-config.toml` gets the six commented defaults under `[pieria.retrieval]`, matching how
every other key there is documented.

---

## 7. Testing

Per repository convention: `*Tests` suffix, narrow slice/unit tests over `@SpringBootTest`, stub
gateways rather than a live model, and **no test seams in production code** — everything goes through
the real public surface.

**`SemanticRescorer`** — blend arithmetic against hand-computed values; missing-vector candidates keep
their RRF position and are not penalised; `w = 0` short-circuits to identity; determinism for a fixed
input; empty and single-element inputs.

**`ModelReranker`** — bucketing puts `ESSENTIAL` above `RELATED` and preserves incoming order within a
bucket; `IRRELEVANT` dropped; unanimous `IRRELEVANT` returns the input unchanged and logs; misaligned
label count treated as failure; unparseable line becomes `RELATED`; empty gateway result is
pass-through; snippet truncation applied before the prompt.

**Degradation** — a throwing stub gateway and a deliberately slow one both return RRF order and do not
fail the recall.

**`RetrievalService`** — flag off produces output identical to today (same ids, same order, same
scores); flag on hands a reranked top-K downstream; a candidate ranked below `limit` by RRF can be
promoted into the result; `EVIDENCE` makes no additional model call (asserted with a counting stub
gateway); `ANALYZED`/`SYNTHESIZED` make exactly one.

**Diagnostics** — rerank fields present under `debug=true`, absent otherwise; channel provenance
preserved through the stage.

**Config** — `ConfigSchemaTests` for the 12 new entries (well-formed, valid kinds, both scopes,
`rerank` section present); `GlobalConfigApiTests` for read-back and write-through to
`pieria.properties` with restart-pending flagging; profile config API tests for whitelist acceptance,
PUT/GET round-trip, and a `rerank-*` typo still yielding 400; `ConfigRecordDriftTests` for
`isEmpty()`.

`./gradlew test` must pass before every commit. Do not run `nativeCompile`, `nativeDist`, or
`deployLocal`.

**Measurement** is the LoCoMo harness with two daemon config files (`--config=<path>`), comparing
evidence-support rate, answer verdicts, and the recall latency line with the stage on versus off. No
new harness plumbing is needed.

---

## 8. Acceptance criteria

1. With `rerank-enabled=false`, recall output is byte-identical to the current pipeline.
2. With it on, a candidate RRF ranked below `limit` can be promoted into the returned evidence.
3. `EVIDENCE` recalls make no model call beyond the query embedding.
4. The model reranker uses the small/fast tier, never the synthesis model.
5. Every failure mode in the D6 table degrades to RRF order and still returns a result.
6. Channel provenance survives the stage and appears in debug diagnostics.
7. All six knobs are editable on both the global and the per-profile console config pages.

---

## 9. Out of scope

- A dedicated reranker model. The `Reranker` seam is where it lands; activate only if eval shows the
  small tier underperforms.
- MMR diversification (D5).
- `replace` / `blend` combination modes (D2 — superseded).
- The memory-only-vs-mixed rerank flag suggested in POTENTIAL_FEATURES #3. Code and memory candidates
  competing for one context budget is the stated reason to rerank at all; splitting them would
  defeat it.
- Lifting the other 28 profile-scoped config keys onto the global console page (§6).
- Any change to ingestion, the retrieval channels, RRF itself, or the synthesis prompt contract.

---

## 10. Risks

- **Defaults are guesses until eval data exists.** `rerankSemanticWeight`, `rerankWindow`, and the
  label prompt are provisional in exactly the way the RRF weights were. The A/B configs are the
  instrument; treat the first numbers as a starting point, not a result.
- **Added read-path latency at `ANALYZED`+.** One small-model call on every recall that synthesizes.
  The `rerank-model-enabled=false` path is the comparison baseline and the escape hatch.
- **Prompt sensitivity.** A three-way label from a 4-8B model is more robust than a numeric score but
  is still prompt-dependent. The lenient-parse and unanimous-irrelevance rules bound the damage; the
  eval harness is what tells us whether the labels carry signal at all.
- **`RecallCandidate.score` no longer implies list order.** Verified safe today (D2), but it is an
  invariant a future reader could reasonably assume. It must be stated in the javadoc, not left to be
  rediscovered.
