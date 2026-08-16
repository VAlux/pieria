# Retrieval–memorization baseline

How we measure end-to-end quality of the ingestion → retrieval → synthesis loop so that
prompt tweaks, RRF-weight changes, and model swaps can be judged against a fixed reference
instead of guessed at. This doc defines the protocol; the harness that produces the numbers
lives in `modules/eval` (see its `README.md`).

## Metric hierarchy

One north-star metric scores the loop the way a user experiences it; the rest localize *where* it
went wrong.

| Metric | What it measures | Role |
|---|---|---|
| **Accuracy** | Fraction of questions answered as they should have been | **North star** |
| Hallucination rate | Fraction that asserted something other than the gold answer | Safety counterpart |
| Abstention rate | Fraction that declined where an answer was expected | Honest-failure rate |
| Gate 1 — extraction coverage | Did the gold fact survive ingestion at all? | Diagnostic: did we *keep* it? |
| Gate 2 — retrieval recall | Of those, did recall surface it? | Diagnostic: did we *find* it? |
| Gate 3 — synthesis accuracy | Of those, did the answer state it? | Diagnostic: did we *say* it? |
| Ingestion / recall latency | Cost | Budget guardrail |

Every metric is also broken down **per LoCoMo category** — 1 multi-hop, 2 temporal, 3 open-domain,
4 single-hop, 5 adversarial — which is usually where a regression localizes first.

### Why three gates rather than one score

Accuracy alone cannot distinguish "the extractor discarded the detail" from "the ranker buried it"
from "synthesis had it and fluffed the answer" — and those call for three completely different fixes.
The gates are **conditional**: gate 2 is only asked of questions that passed gate 1, gate 3 only of
those that passed gate 2. A low gate 1 with high gates 2 and 3 is an extraction-policy result, not a
retrieval regression, and reading it as one would send tuning in the wrong direction.

### Why abstention is scored apart from a wrong answer

For a memory layer they mean opposite things: declining to answer a fact that was never stored is
*correct behaviour*, while asserting a wrong one is a hallucination. A single "unfaithful" bucket
scores them identically and hides which way a change moved the system.

### Category 5 is scored inverted, on purpose

LoCoMo's adversarial questions attribute a real fact to the wrong speaker, and the dataset's
`adversarial_answer` field is the **trap** the question baits, not a gold answer. Declining is the
correct outcome; asserting the trap is the failure. Roughly a quarter of the corpus is category 5, so
treating that field as gold inverts the metric across a quarter of the benchmark.

## What each metric can be trusted on

- **Accuracy / hallucination / abstention** — trustworthy, subject to the self-grading caveat below.
- **The three gates** — LLM-judged support checks ("is the gold answer derivable from these notes?"),
  which is what makes them able to fire at all. The token-containment matcher they replaced could
  not: extraction rewrites turns tersely enough that measured containment against a gold evidence
  turn tops out near **0.50**, so a 0.6 threshold scored every question a miss regardless of quality.
  Token overlap survives only as the *ranker* that shortlists 20 stored memories for gate 1, where no
  threshold is applied and the judge makes the call.
- **Retrieval metrics on small subsets** — treat with suspicion. `--recall-limit` defaults to 10, and
  a one-conversation slice may store only a few dozen memories, so recall returns most of the corpus
  and ranking is barely exercised. Gate 2 is only meaningful when stored memories greatly exceed the
  recall limit.
- **Extraction precision / recall** — not reported. LoCoMo ships no gold extraction set, so they
  would be vacuous. Gate 1 measures per-question coverage instead.

## Anchor: LoCoMo

The baseline is anchored on **LoCoMo** — multi-session, two-human dialogue, the closest public
dataset to Pieria's actual use.

Dataset is not checked in. Place `locomo10.json` under `datasets/locomo/` at the repo root
(git-ignored), or pass `--dataset=<path>`.

Each turn is ingested with its session date twice over — prefixed to the text
(`[1:56 pm on 8 May, 2023] Caroline: …`) and sent as the turn's `MessageDto.timestamp`, so the
daemon's deterministic relative-date rewriting anchors on 2023 instead of the ingest wall clock.
**This is a protocol change: numbers recorded before it are not comparable to numbers recorded after
it.**

One temporal gap remains: a memory's own `createdAt` is still its *store* time, so the temporal facts
injected at synthesis describe when Pieria learned a fact, not when the fact happened. Date/duration
questions can still be answered against the wrong "now".

## Judge: local Ollama

Every score comes from the **same local model** the pipeline runs on — `ModelGateway.judgeAnswer`
for the verdict and `ModelGateway.judgeEvidenceSupport` for both gates — so the baseline needs no API
key or network.

> ⚠️ **Self-grading caveat.** The judge and the pipeline share a model, so it grades output from
> its own family and may be lenient. The absolute accuracy number is therefore optimistic —
> use it for **relative** comparison (did this change move the number?), not as an absolute quality
> claim. If we later want an absolute number, re-run with a stronger, independent hosted judge and
> record it as a separate baseline. Because scoring is a separate pass over a written report, that
> re-judgement does not require re-driving the daemon.

> ⚠️ **LoCoMo is a regression harness, not a quality score.** It is long-context QA over human
> chit-chat; systems that top its leaderboard store close to everything. Pieria's extractor
> deliberately keeps only what is *durable and worth remembering across sessions*, so gate 1 caps
> the achievable accuracy by design. Use these numbers to detect that a change moved the pipeline —
> never as a target to optimize, since raising the absolute score would mean storing conversational
> trivia and regressing the product.

## Reproducibility — pin everything

A baseline is only meaningful if it can be reproduced. The written report already carries most of
this — it embeds the full `BenchmarkConfig` and the provider/model identity of the run — so pin the
rest alongside the numbers:

- **Dataset slice** — the `--conversations` / `--sessions` / `--questions` / `--categories` flags.
  A headline baseline uses the full dataset (no subset flags).
- **Daemon config** — the `--config=<file>` the run used. Without it the run measures the *bundled
  defaults*, which are not the pipeline you deploy if you have a config file; a baseline row should
  say which of the two it describes.
- **Models** — extraction, synthesis and embedding model + embedding dimension (in the report).
- **Retrieval config** — RRF k + channel weights, top-K, and any query-analysis settings.
- **Ingestion config** — chunk size, detail-pass thresholds, extraction concurrency.
- **Judge model** — the local model id used for faithfulness.
- **Tier prices** — `pieria.stats.spend.<tier>.input-price` / `.output-price` in the benchmarked
  config. Without them the report still records tokens, but the cost column reads zero; a baseline
  row should carry the spend that produced it, since a quality gain bought at 3× the cost is a
  different result from the same gain for free.
- **Pipeline temperature** — pin to 0 where the provider allows.
- **Runs** — `--runs=3` to smooth stochastic output; the summary pools all runs' questions.
- **Provenance** — git SHA of the run and a hash/snapshot of the config above.

## How to run

Requires Ollama running with the configured models and a local dataset file. Never runs in CI.

```bash
# Headline baseline: the whole dataset, three runs pooled.
./gradlew :eval:locomo --args="--runs=3"

# Smoke run while iterating (minutes, not hours).
./gradlew :eval:locomo --args="--conversations=1 --sessions=3 --questions=10 --no-judge"

# Re-render a written report as HTML.
./gradlew :eval:locomoReport --args="pieria-eval-reports/evaluation-....json"
```

The run logs a `LoCoMo done — accuracy=…` summary, the `extracted → retrieved → answered` funnel and
the per-category breakdown, and writes a `.json` / `.html` pair into the git-ignored
`pieria-eval-reports/`. Open the HTML to read the run; copy the summary numbers into the results
table below and commit *that* (not the raw report).

## Cadence — two loops

- **Inner (every change, in CI):** the adapter/config/renderer unit tests via `./gradlew :eval:test`.
  No network, no daemon, cheap; catches mechanical regressions in dataset parsing, subsetting and
  report rendering.
- **Outer (manual / periodic):** the live run above — a real daemon booted on a throwaway DB, driven
  over HTTP. Produces the headline correctness number. Slow and costs tokens — run it on meaningful
  changes, then diff against the committed baseline. Diagnostics explain any move in faithfulness.

## Results

Append a row per baseline run. Keep the config that produced each row (SHA + settings) so rows
are comparable.

| Date | Git SHA | Dataset slice | Models (extract / synth / embed) | Accuracy | Halluc. | Extracted | Retrieved | Answered | Ingest | Recall | Cost | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| _tbd_ | _tbd_ | LoCoMo (10) | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ | baseline v0 |

> **Protocol change — earlier numbers are not comparable.** Two scoring fixes landed together:
> category-5 adversarials were being scored inverted (the trap counted as the gold answer), and the
> token-containment evidence matcher could never fire, so hit-rate/MRR read 0 regardless of quality.
> Any faithfulness/hit-rate/MRR figure recorded before this change measures neither what it claimed
> nor anything the current columns report.
