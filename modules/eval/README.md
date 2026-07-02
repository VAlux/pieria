# eval

The `eval` module is an offline evaluation harness for Pieria's ingestion and retrieval pipelines. It runs the real `IngestionService` and `RetrievalService` — exactly as the daemon does — against controlled fixtures and benchmark datasets, then produces a structured `EvaluationReport` with precision, recall, MRR, answer faithfulness, latency, and token-usage metrics.

The harness exists because prompt changes, RRF weight tuning, and model swaps all affect quality in non-obvious ways. Every such change should be measured, not guessed.

## Design

The `eval` module has no web server and no Spring Boot application entry point of its own. It is a library of evaluation infrastructure that instantiates daemon internals directly by depending on the daemon's plain jar (`:daemon` publishes both a `bootJar` and a `jar` for this reason).

Two run modes are supported:

**Deterministic (CI default)** — uses `PinnedEvaluationModelGateway`, which replays expected answers from the fixture JSON instead of calling any model. No Ollama, no network, no randomness. This is the mode used by `./gradlew test`.

**Live (explicit)** — uses the daemon's real `OpenAiModelGateway` (or any other `ModelGateway` implementation). Activated by setting `PIERIA_LIVE_EVAL=1` and running `:eval:test`. Benchmark tests filtered by `*Benchmark*` are excluded from the normal test run and must be triggered explicitly.

## Key classes

| Class | Role |
|---|---|
| `EvaluationRunner` | Orchestrates ingestion + retrieval for a list of `EvaluationFixture` objects and produces an `EvaluationReport` |
| `EvaluationFixture` | A single test case: a conversation transcript, expected extracted memories, and recall queries with expected answers |
| `EvaluationFixtureLoader` | Deserializes fixture JSON from `src/test/resources/evaluation/` |
| `EvaluationReport` | Per-fixture and aggregate metrics: extraction precision/recall, retrieval hit rate, MRR, answer faithfulness, latency, token usage |
| `EvaluationReportWriter` | Writes a `EvaluationReport` to a JSON file or stdout |
| `PinnedEvaluationModelGateway` | Deterministic gateway that replays fixture-expected answers for CI |
| `InMemoryEvaluationMemoryStore` | Ephemeral in-memory `MemoryStore` (backed by `SqliteMemoryStore` on a `:memory:` database); a fresh instance is created per fixture so memories never leak between tests |
| `LiveModelGatewayFactory` | Boots a minimal Spring context to obtain the daemon's `OpenAiModelGateway` for live benchmark runs |
| `BenchmarkRunner` | Entry point for running a full benchmark dataset end-to-end with a live model |
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

Faithfulness is judged by `ModelGateway.judgeAnswerFaithfulness`. In deterministic mode this falls back to a case-insensitive string match; in live mode it delegates to the model.

## Running

```bash
# Deterministic CI run (no Ollama required) — includes the offline *BenchmarkAdapterTests.
# The live BenchmarkRunnerLiveTests self-skip here via @EnabledIfEnvironmentVariable(PIERIA_LIVE_EVAL).
./gradlew :eval:test

# Live LoCoMo baseline run against local Ollama (requires Ollama running with configured models).
# Uses datasets/locomo/locomo10.json at the repo root when present; skipped (not failed) if absent.
PIERIA_LIVE_EVAL=1 ./gradlew :eval:test --tests "*BenchmarkRunnerLiveTests*"

# LongMemEval stays opt-in behind its dataset env var.
PIERIA_LIVE_EVAL=1 PIERIA_LONGMEMEVAL_DATASET=datasets/longmemeval/longmemeval_s.json ./gradlew :eval:test --tests "*BenchmarkRunnerLiveTests*"
```

Benchmark datasets (LoCoMo, LongMemEval) are not checked into the repository (`datasets/` is git-ignored). Place `locomo10.json` under `datasets/locomo/` at the repo root; the LoCoMo run reads it there by default (override with `PIERIA_LOCOMO_DATASET`). Sample files for unit testing the adapters live in `src/test/resources/evaluation/benchmarks/`. The averaged report is written to `pieria-eval-reports/`. See `docs/eval/BASELINE.md` for the baseline protocol.
