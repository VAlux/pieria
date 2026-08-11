# Retrieval–memorization baseline

How we measure end-to-end quality of the ingestion → retrieval → synthesis loop so that
prompt tweaks, RRF-weight changes, and model swaps can be judged against a fixed reference
instead of guessed at. This doc defines the protocol; the harness that produces the numbers
lives in `modules/eval` (see its `README.md`).

## Metric hierarchy

One north-star metric scores the loop the way a user experiences it; the rest are diagnostics
that tell you *why* an answer was right or wrong.

| Metric | What it measures | Role |
|---|---|---|
| **Answer faithfulness** | Synthesized answer vs. gold answer, LLM-judged | **North star** |
| Retrieval hit-rate | Fraction of gold evidence turns surfaced in recall | Diagnostic: did we *find* it? |
| MRR | Reciprocal rank of the first matching memory | Diagnostic: was it ranked well? |
| Ingestion / recall latency | Cost | Budget guardrail |

A drop in faithfulness sends you to the diagnostics to localize the regression (retrieve vs.
synthesize). Every metric is also broken down **per LoCoMo category** — 1 multi-hop, 2 temporal,
3 open-domain, 4 single-hop, 5 adversarial — which is usually where a regression localizes first.

## What each metric can be trusted on

- **Answer faithfulness** — trustworthy, subject to the self-grading caveat below.
- **Retrieval hit-rate / MRR** — meaningful because LoCoMo's evidence dialog ids are resolved to the
  referenced turn text. Evidence is matched by **stopword-filtered token containment ≥ 0.6** against
  retrieved memory text, not exact string equality, because extraction rewrites turns into terse
  memories. Treat hit-rate as a *lower bound*: a semantically-correct memory that shares fewer than
  60% of the evidence's content words is scored as a miss.
- **Extraction precision / recall** — not reported. LoCoMo ships no gold extraction set, so they
  would be vacuous. The report records the number of memories stored per conversation instead.

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

Faithfulness is judged by the **same local model** the pipeline runs on
(`ModelGateway.judgeAnswerFaithfulness`), so the baseline needs no API key or network.

> ⚠️ **Self-grading caveat.** The judge and the pipeline share a model, so it grades output from
> its own family and may be lenient. The absolute faithfulness number is therefore optimistic —
> use it for **relative** comparison (did this change move the number?), not as an absolute quality
> claim. If we later want an absolute number, re-run with a stronger, independent hosted judge and
> record it as a separate baseline.

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

The run logs a `LoCoMo done — faithfulness=… hitRate=… mrr=…` summary plus the per-category
breakdown, and writes a `.json` / `.html` pair into the git-ignored `pieria-eval-reports/`. Open the
HTML to read the run; copy the summary numbers into the results table below and commit *that* (not
the raw report).

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

| Date | Git SHA | Dataset slice | Models (extract / synth / embed) | Faithfulness | Hit-rate | MRR | Ingest | Recall | Notes |
|---|---|---|---|---|---|---|---|---|---|
| _tbd_ | _tbd_ | LoCoMo (10) | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ | baseline v0, session dates ingested |
