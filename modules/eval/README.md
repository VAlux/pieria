# eval

The `eval` module is an evaluation harness for Pieria's ingestion and retrieval pipelines. It drives a **real running daemon** over HTTP — booting the full daemon web stack on a throwaway database and POSTing to `/ingest` and `/recall` exactly as a harness or the console would — then produces a structured `EvaluationReport` with precision, recall, MRR, answer faithfulness, latency, and token-usage metrics.

The harness exists because prompt changes, RRF weight tuning, and model swaps all affect quality in non-obvious ways. Every such change should be measured, not guessed. Driving the real daemon (rather than instantiating the services against a stub store) is what makes the numbers reflect the deployed pipeline: sqlite-vec + FTS5 + graph + RRF fusion under the daemon's own configuration.

## Design

The `eval` module has no application entry point of its own. It depends on the daemon's plain jar (`:daemon` publishes both a `bootJar` and a `jar`) to boot `PieriaApplication`, and on `:shared` for the HTTP request/response records it (de)serializes.

There is a **single run mode**: live, against a real daemon. `LiveDaemon` boots `PieriaApplication` as a web server on a random loopback port pointed at a fresh temp DB; `DaemonEvalClient` drives it over HTTP. It requires a reachable model provider (Ollama by default) and so is **never run in CI** — the live test self-disables unless `PIERIA_LIVE_EVAL` is set. The only tests that run in CI are the pure adapter/fixture-loader unit tests (`*BenchmarkAdapterTests`, `EvaluationFixtureLoaderTests`), which parse dataset/fixture JSON and touch no daemon.

Answer faithfulness is judged as a **separate pass**: the daemon run records each query's synthesized answer, and `FaithfulnessJudgeRunner` scores it afterwards with a judge `ModelGateway` (deliberately separate from the daemon under test). This means answers can be re-judged without re-driving the expensive end-to-end run.

## Key classes

| Class | Role |
|---|---|
| `LiveDaemon` | Boots the real `PieriaApplication` web stack on a random loopback port + throwaway temp DB; `AutoCloseable`, cleans up on close |
| `DaemonEvalClient` | HTTP client that drives the daemon: `/ingest`, `/recall` (debug), and a `/stats`-based vectorization-outbox barrier |
| `EvaluationRunner` | Per-fixture orchestration over `DaemonEvalClient` (ingest → await vectorization → recall) producing an `EvaluationReport` |
| `EvaluationFixture` | A single test case: a conversation transcript, expected extracted memories, and recall queries with expected answers |
| `EvaluationFixtureLoader` | Deserializes fixture JSON from `src/test/resources/evaluation/` |
| `EvaluationReport` | Per-fixture and aggregate metrics: extraction precision/recall, retrieval hit rate, MRR, answer faithfulness, latency, token usage |
| `EvaluationReportWriter` | Writes an `EvaluationReport` to a JSON file or stdout |
| `FaithfulnessJudgeRunner` | Second pass: judges recorded answers with a judge `ModelGateway` and fills in faithfulness |
| `LiveModelGatewayFactory` | Boots a minimal non-web Spring context to obtain the daemon's `OpenAiModelGateway` as the faithfulness judge |
| `BenchmarkRunner` | Entry point for running a full benchmark dataset end-to-end against a `LiveDaemon` |
| `LoCoMoBenchmarkAdapter` | Converts LoCoMo dataset entries to `EvaluationFixture` objects |
| `LongMemEvalBenchmarkAdapter` | Converts LongMemEval dataset entries to `EvaluationFixture` objects |

## Fixture format

Fixtures live in `src/test/resources/evaluation/`. Each file is a JSON object:

```json
{
  "name": "project-preferences",
  "profileName": "pieria-eval",
  "sessionId": "fixture-project-preferences",
  "transcript": [
    { "role": "user", "content": "..." },
    { "role": "assistant", "content": "..." }
  ],
  "expectedMemories": [
    { "type": "fact", "content": "...", "topicKey": "..." }
  ],
  "recalls": [
    {
      "query": "...",
      "expectedEvidence": ["..."],
      "expectedAnswer": "..."
    }
  ]
}
```

## Metrics

| Metric | Definition |
|---|---|
| Extraction precision | `true_positives / actual_extracted` |
| Extraction recall | `true_positives / expected_memories` |
| Retrieval hit rate | fraction of expected evidence memories that appeared in the recall result |
| MRR | Mean Reciprocal Rank of the first expected evidence memory in the ranked result |
| Answer faithfulness | fraction of recall queries where the synthesized answer is judged faithful to the expected answer |

Faithfulness is judged by `ModelGateway.judgeAnswerFaithfulness` in the `FaithfulnessJudgeRunner` pass, after the daemon run records each answer. Until that pass runs, the flag is `false` (unjudged).

Ingestion is driven through the daemon's async endpoint (`POST /ingest/async` + task polling), because a fixture's extraction pipeline can take minutes on a local model — too long for a single blocking request. The async task reports only the stored **count**, not the memory contents, so content-level extraction true-positives are not computed; LoCoMo and LongMemEval carry no gold extraction set anyway, so extraction precision/recall are vacuous for them and only the count is informative.

## Running

```bash
# CI run: pure adapter/fixture-loader unit tests only (no daemon, no Ollama).
# The live DaemonBenchmarkLiveTests self-skip via @EnabledIfEnvironmentVariable(PIERIA_LIVE_EVAL).
./gradlew :eval:test

# Live LoCoMo baseline against a real daemon (boots the daemon in-process on a temp DB; requires
# Ollama running with the configured models). Uses datasets/locomo/locomo10.json at the repo root
# when present; skipped (not failed) if absent.
PIERIA_LIVE_EVAL=1 ./gradlew :eval:test --tests "*DaemonBenchmarkLiveTests*"

# LongMemEval stays opt-in behind its dataset env var.
PIERIA_LIVE_EVAL=1 PIERIA_LONGMEMEVAL_DATASET=datasets/longmemeval/longmemeval_s.json ./gradlew :eval:test --tests "*DaemonBenchmarkLiveTests*"
```

The live daemon boots against the daemon's own configuration (default: Ollama with the models in `application.properties`) on a throwaway temp DB, so results reflect the deployed pipeline. It resolves the sqlite-vec `vec0` extension from the classpath resources bundled by `:daemon`; if that fails on your platform, point `pieria.vec.extension-path` (or `PIERIA_VEC_EXTENSION`) at your installed `vec0`.

Benchmark datasets (LoCoMo, LongMemEval) are not checked into the repository (`datasets/` is git-ignored). Place `locomo10.json` under `datasets/locomo/` at the repo root; the LoCoMo run reads it there by default (override with `PIERIA_LOCOMO_DATASET`). Sample files for unit testing the adapters live in `src/test/resources/evaluation/benchmarks/`. The averaged raw report and the judged report are written to `pieria-eval-reports/`. See `docs/eval/BASELINE.md` for the baseline protocol.
