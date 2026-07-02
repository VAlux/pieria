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
| Extraction precision / recall | Extracted memories vs. gold extraction set | Diagnostic: did we *capture* it? |
| Ingestion / recall latency, tokens | Cost | Budget guardrail |

A drop in faithfulness sends you to the diagnostics to localize the regression (capture vs.
retrieve vs. synthesize).

## What each metric can be trusted on

- **Answer faithfulness** — trustworthy on every dataset.
- **Retrieval hit-rate / MRR** — meaningful on LoCoMo (evidence dialog ids are resolved to turn
  text) and LongMemEval (per-turn `has_answer` evidence), and on the hand fixtures. Evidence is
  matched by **stopword-filtered token containment ≥ 0.6** against retrieved memory text, not exact
  string equality, because extraction rewrites turns into terse memories. Treat hit-rate as a
  *lower bound*: a semantically-correct memory that shares fewer than 60% of the evidence's content
  words is scored as a miss.
- **Extraction precision / recall** — only meaningful on the hand fixtures (LoCoMo/LongMemEval ship
  no gold extraction set; the harness reports their extraction metrics as vacuous). With only two
  hand fixtures today this is not yet a real baseline — grow the set to ~15–20 covering each memory
  type, supersession, and temporal cases before reading extraction numbers seriously.

## Anchor: LoCoMo

The headline baseline is anchored on **LoCoMo** — multi-session, two-human dialogue, the closest
public dataset to Pieria's actual use. LongMemEval can be added later as a second anchor.

Dataset is not checked in. Place `locomo10.json` under `datasets/locomo/` at the repo root (git-ignored).

## Judge: local Ollama

Faithfulness is judged by the **same local model** the pipeline runs on (`ModelGateway.judgeAnswerFaithfulness`),
so the baseline needs no API key or network.

> ⚠️ **Self-grading caveat.** The judge and the pipeline share a model, so it grades output from
> its own family and may be lenient. The absolute faithfulness number is therefore optimistic —
> use it for **relative** comparison (did this change move the number?), not as an absolute quality
> claim. If we later want an absolute number, re-run with a stronger, independent hosted judge and
> record it as a separate baseline.

## Reproducibility — pin everything

A baseline is only meaningful if it can be reproduced. Record all of the following alongside the
numbers:

- **Dataset slice** — exact file + which samples (e.g. all 10 LoCoMo conversations).
- **Models** — extraction model, synthesis model, embedding model + embedding dimension.
- **Retrieval config** — RRF k + channel weights, top-K, and any query-analysis settings.
- **Ingestion config** — chunk size, detail-pass thresholds, extraction concurrency.
- **Judge model** — the local model id used for faithfulness.
- **Pipeline temperature** — pin to 0 where the provider allows.
- **Runs** — `BenchmarkRunner.averageRuns` with `runCount = 3` to smooth stochastic output.
- **Provenance** — git SHA of the run and a hash/snapshot of the config above.

## How to run

Requires Ollama running with the configured models and a local dataset file. Never runs in CI.

```bash
# Averaged LoCoMo run (runCount defaults to 3); reads datasets/locomo/locomo10.json, writes to
# pieria-eval-reports/. Skipped (not failed) if the dataset file is absent.
PIERIA_LIVE_EVAL=1 ./gradlew :eval:test --tests "*BenchmarkRunnerLiveTests*"

# Or invoke the runner directly:
#   java -cp <classpath> dev.alvo.pieria.evaluation.BenchmarkRunner locomo datasets/locomo/locomo10.json 3
```

The run logs a one-line `LoCoMo baseline — faithfulness=… hitRate=… mrr=…` summary and writes the
full report into the git-ignored `pieria-eval-reports/`. Copy the summary numbers into the results
table below and commit *that* (not the raw report).

## Cadence — two loops

- **Inner (every change, in CI):** deterministic hand fixtures via `./gradlew :eval:test`. No
  network, cheap, catches mechanical regressions in extraction and retrieval. Grow this set.
- **Outer (manual / periodic):** the live LoCoMo run above. Produces the headline correctness
  number. Slow and costs tokens — run it on meaningful changes, then diff against the committed
  baseline. Diagnostics explain any move in faithfulness.

## Results

Append a row per baseline run. Keep the config that produced each row (SHA + settings) so rows
are comparable.

| Date | Git SHA | Dataset slice | Models (extract / synth / embed) | Faithfulness | Hit-rate | MRR | Ingest ms | Recall ms | Notes |
|---|---|---|---|---|---|---|---|---|---|
| _tbd_ | _tbd_ | LoCoMo (10) | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ | baseline v0 |
