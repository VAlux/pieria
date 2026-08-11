# eval

The `eval` module is Pieria's **LoCoMo benchmark harness**. It drives a *real running daemon* over
HTTP — booting the full daemon web stack on a throwaway database and POSTing to `/ingest/async` and
`/recall` exactly as a harness or the console would — then writes a JSON report and a self-contained
HTML rendering of it.

The harness exists because prompt changes, RRF weight tuning, and model swaps all affect quality in
non-obvious ways. Every such change should be measured, not guessed. Driving the real daemon (rather
than instantiating the services against a stub store) is what makes the numbers reflect the deployed
pipeline: sqlite-vec + FTS5 + graph + RRF fusion under the daemon's own configuration.

It has no application entry point of its own beyond the benchmark: it depends on the daemon's plain
jar (`:daemon` publishes both a `bootJar` and a `jar`) to boot `PieriaApplication`, and on `:shared`
for the HTTP request/response records it (de)serializes. It is **never run in CI** — it needs a local
dataset file and a reachable model provider. The only tests that run in CI are the pure
adapter/config/renderer unit tests, which touch no daemon and no network.

## Running

```bash
# Quick smoke run: one conversation, its first three sessions, ten questions, no LLM judging.
./gradlew :eval:locomo --args="--conversations=1 --sessions=3 --questions=10 --no-judge"

# Representative subset, judged.
./gradlew :eval:locomo --args="--conversations=3 --questions=25"

# Full baseline, three runs averaged.
./gradlew :eval:locomo --args="--runs=3"

# Check what a slice actually selects before committing hours to it.
./gradlew :eval:locomo --args="--conversations=2 --sessions=4 --questions=20 --dry-run"

# All flags.
./gradlew :eval:locomo --args="--help"

# Re-render an existing report as HTML, without re-running the benchmark.
./gradlew :eval:locomoReport --args="pieria-eval-reports/evaluation-2026-08-11T10-00-00Z.json"

# CI run: adapter/config/renderer unit tests only.
./gradlew :eval:test
```

The dataset is not checked in (`datasets/` is git-ignored). Place `locomo10.json` under
`datasets/locomo/` at the repo root, or point `--dataset=<path>` at it. Reports land in
`pieria-eval-reports/` (also git-ignored) as a `.json` / `.html` pair sharing one timestamped name.

## Which pipeline gets measured

The daemon under test boots on a throwaway temp `PIERIA_HOME`, so the operator's real store is never
touched. That isolation has a consequence worth knowing: `application.properties` imports
`${pieria.app-data.config-dir}/pieria.properties`, and config-dir points into the temp home — so
**without `--config` the run measures the daemon's bundled defaults, not your installed
configuration**. If you run a different provider or different models than the shipped defaults, pass
your config file explicitly:

```bash
# macOS; Linux: ~/.local/share/pieria/config/pieria.properties
./gradlew :eval:locomo --args="--config='$HOME/Library/Application Support/pieria/config/pieria.properties' --conversations=1 --sessions=3 --questions=10"
```

Note the inner quotes: Gradle splits `--args` on whitespace, and the default macOS config path contains
a space. Without them the path arrives as two arguments and the run aborts (loudly — an unparseable
flag is never ignored).

The file is layered in as a Spring `spring.config.additional-location`, so it can change models,
prompts and retrieval weights. It cannot redirect storage: the temp-home paths are passed as
command-line arguments, which outrank every config file. The judge gateway boots against the same
config, so faithfulness is judged by the model that config names. The resolved provider and model
names are recorded in every report's `models` block.

The live daemon resolves the sqlite-vec `vec0` extension from the
classpath resources bundled by `:daemon`; if that fails on your platform, point
`pieria.vec.extension-path` (or `PIERIA_VEC_EXTENSION`) at your installed `vec0`.

## Configuration

| Flag | Default | Meaning |
|---|---|---|
| `--dataset=<path>` | `datasets/locomo/locomo10.json` | dataset file |
| `--config=<path>` | bundled defaults | daemon config file to benchmark against |
| `--conversations=<n\|ids>` | all | first *n* conversations, or an explicit `conv-26,conv-30` list |
| `--sessions=<n>` | all | keep only sessions `1..n` of each conversation |
| `--questions=<n>` | all | questions per conversation, sampled evenly across the QA list |
| `--categories=<1,2,3,4,5>` | all | question categories to keep |
| `--runs=<n>` | `1` | repeat the benchmark and pool the results |
| `--recall-limit=<n>` | `10` | memories requested per recall |
| `--out=<dir>` | `pieria-eval-reports` | report directory |
| `--no-judge` | off | skip the LLM faithfulness pass |
| `--dry-run` | off | print the selected slice and exit, without booting the daemon |

**Cost model.** A full run is 10 conversations, ~5 900 turns and ~2 000 questions — hours against a
local model. Ingestion dominates and is *per conversation*, so `--conversations` and `--sessions` are
the real dials; `--questions` only trims the (much cheaper) recall phase.

Unknown flags and malformed values fail immediately rather than being ignored — a typo'd subset flag
would otherwise run for hours on the wrong slice. `--sessions` drops any question whose gold evidence
lives in a session that was cut, so a truncated run's score stays honest. `--questions` samples with a
stride rather than taking a head slice, because LoCoMo's `qa` array is loosely ordered by category.

## Key classes

| Class | Role |
|---|---|
| `BenchmarkRunner` | Entry point: parse config → boot daemon → run → judge → write JSON + HTML |
| `BenchmarkConfig` | The `--flag=value` surface, copied verbatim into the report |
| `LoCoMoBenchmarkAdapter` | Parses `locomo10.json` into `EvaluationFixture`s, applying the subset filters |
| `EvaluationFixture` | One conversation: the transcript to ingest and the questions to recall |
| `LiveDaemon` | Boots the real `PieriaApplication` web stack on a random loopback port + throwaway `EvalHome` |
| `EvalHome` | The throwaway `PIERIA_HOME` both in-process Spring contexts run against |
| `DaemonEvalClient` | HTTP client: `/ingest/async` + task polling, `/recall`, and a `/stats`-based vectorization barrier |
| `EvaluationRunner` | Per-conversation orchestration (ingest → await vectorization → recall) and scoring |
| `EvaluationReport` | The report records and the aggregation helpers (`score`, `scoreByCategory`) |
| `EvaluationReportWriter` | Writes and reads the JSON report |
| `HtmlReportWriter` | Renders a report into a self-contained HTML page with Thymeleaf |
| `FaithfulnessJudgeRunner` | Second pass: judges recorded answers and fills in faithfulness |
| `LiveModelGatewayFactory` | Boots a minimal non-web Spring context to obtain the judge `ModelGateway` |

## Metrics

| Metric | Definition |
|---|---|
| Answer faithfulness | fraction of questions whose synthesized answer is judged faithful to the gold answer |
| Retrieval hit rate | fraction of gold evidence turns that appeared in the recall result |
| MRR | mean reciprocal rank of the first matching memory |

Every metric is reported overall, per LoCoMo category (1 multi-hop, 2 temporal, 3 open-domain,
4 single-hop, 5 adversarial), and per conversation. The overall and per-category figures pool every
question (a micro-average); the per-conversation figures cover that conversation's questions only.

Evidence is matched by **stopword-filtered token containment ≥ 0.6** against retrieved memory text,
not exact string equality, because extraction rewrites turns into terse memories. Treat hit-rate as a
*lower bound*: a semantically-correct memory that shares fewer than 60% of the evidence's content
words is scored as a miss.

Answer faithfulness is judged as a **separate pass**: the daemon run records each question's
synthesized answer, and `FaithfulnessJudgeRunner` scores it afterwards with a judge `ModelGateway`
(booted only after the daemon under test has shut down, so the two never compete for the provider).
This means answers can be re-judged without re-driving the expensive end-to-end run. Until that pass
runs — or with `--no-judge` — the flag is `false` (unjudged).

Extraction precision/recall are deliberately **not** reported: LoCoMo ships no gold extraction set, so
they would be vacuous. The report records the number of memories stored per conversation instead.

## Ingestion details

Ingestion is driven through the daemon's async endpoint (`POST /ingest/async` + task polling), because
a conversation's extraction pipeline can take many minutes on a local model — too long for a single
blocking request. The task reports only the stored **count**, which is what the report records.

LoCoMo is a historical corpus — 2023 conversations ingested today — so the harness tells the daemon
when each turn was spoken, in two complementary ways:

1. **The session date is prefixed to the turn text** — `[1:56 pm on 8 May, 2023] Caroline: …` — so
   every chunk the daemon extracts from carries the date in-band. The gold evidence text the harness
   scores against deliberately keeps the *undated* turn body, so the prefix does not influence the
   retrieval metrics.
2. **The session date is sent as the turn's `MessageDto.timestamp`.** This is the one that matters for
   relative dates: `TranscriptNormalizer` rewrites "yesterday"/"today"/"tomorrow" to absolute dates
   deterministically in Java, and without a timestamp it anchors on the ingest wall clock. A 2023
   "yesterday" then lands in the current year, three years off, before the model ever sees the turn.

Both are needed. The prefix alone is not enough — the normalizer runs first and wins. Without either,
LoCoMo's category-2 (temporal) questions, whose gold answers *are* dates, are unanswerable by
construction.

See `docs/eval/BASELINE.md` for the baseline protocol and the results table.
