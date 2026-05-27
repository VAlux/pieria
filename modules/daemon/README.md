# daemon

The `daemon` module is the persistent background service that owns everything stateful in Pieria: the SQLite database, the ingestion pipeline, the retrieval pipeline, and the model gateway. It is the **single writer** of the embedded store and the only process that talks to the model provider (Ollama by default). All other components — the MCP gateway, harness hooks — connect to it over HTTP.

## Responsibilities

- Bind a REST API on `127.0.0.1:8077` (local mode; never a public interface).
- Own the embedded SQLite database with `sqlite-vec` for vector search and FTS5 for full-text search.
- Run the ingestion pipeline: extract, verify, classify, deduplicate, and store memories from conversation transcripts.
- Run the retrieval pipeline: five parallel search channels fused via Reciprocal Rank Fusion, followed by LLM synthesis.
- Async-vectorize new memories via a transactional outbox + virtual-thread worker.
- Perform first-run setup: create app-data directories, check for required Ollama models, print the MCP config snippet.

## Package structure

```
dev.alvo.pieria
├── api/
│   ├── controller/      HealthController, ProfileController, StatusController
│   ├── conversion/      MemoryResponseConverter (domain → HTTP response)
│   └── error/           GlobalExceptionHandler
├── config/              Spring configuration, first-run, DataSource, sqlite-vec extension loader
├── domain/              Pure domain records: Memory, Profile, Message, Chunk, Classification, …
├── ingestion/           IngestionService, Chunker, TranscriptNormalizer, VectorizationWorker
├── model/               ModelGateway interface, OllamaModelGateway
├── retrieval/
│   ├── channel/         Five retrieval channels: FTS memory, FTS message, exact key, direct vector, HyDE vector
│   ├── RetrievalService.java
│   ├── ReciprocalRankFusion.java
│   ├── DeterministicQueryAnalyzer.java
│   └── TemporalExtractor.java
└── storage/             MemoryStore interface, SqliteMemoryStore, SqliteVectorIndex
```

## Key abstractions

**`MemoryStore`** — the single persistence seam. All ingestion writes and retrieval reads go through this interface. Swapping it for a Postgres implementation (Phase 6) requires no changes to the ingestion or retrieval services.

**`ModelGateway`** — provider-agnostic access to chat and embedding models. Two tiers: a small/fast model for structured stages (extraction, verification, classification, query analysis) and a large model for final synthesis only. Default implementation: `OllamaModelGateway`.

**`IngestionService`** — the write path. Given a list of messages and a session ID: generates content-addressed IDs → runs full + detail extraction in parallel (structured concurrency) → verifies each candidate → classifies and keys → handles supersession → stores → enqueues vectorization.

**`RetrievalService`** — the read path. Given a recall query: analyses the query and embeds it in parallel → fans out to five channels in parallel (structured concurrency) → fuses with RRF → synthesizes a natural-language answer.

## REST API

All endpoints are scoped under `/v1/profiles/{name}`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/ingest` | Bulk-extract memories from a conversation transcript |
| `POST` | `/memories` | Store a single memory explicitly |
| `POST` | `/recall` | Run retrieval, return a synthesized answer |
| `GET` | `/memories` | List memories (filter by type and/or session) |
| `DELETE` | `/memories/{id}` | Forget a memory (marks it superseded, never deletes) |
| `GET` | `/export` | Export all memories as NDJSON |
| `GET` | `/pieria-health` | Health: db status + model provider reachability |
| `GET` | `/status` | Operational status: outbox depth, vector search availability |

## Configuration

All properties are in `src/main/resources/application.properties`. Key ones:

| Property | Default | Description |
|---|---|---|
| `pieria.daemon.host` | `127.0.0.1` | Bind address (never change in local mode) |
| `pieria.daemon.port` | `8077` | Listen port |
| `pieria.db.path` | *(OS app-data dir)* | SQLite database file path |
| `pieria.model.chat-small` | `qwen2.5:14b` | Small/fast model for structured stages |
| `pieria.model.chat-large` | `llama3.1:8b` | Large model for synthesis |
| `pieria.model.embedding` | `mxbai-embed-large` | Embedding model |
| `pieria.model.embedding-dimension` | `1024` | Fixes the `FLOAT[n]` vector column width — set once |
| `pieria.retrieval.vector-enabled` | `true` | Disable if `sqlite-vec` is not available |
| `pieria.first-run.enabled` | `true` | Run first-run setup on startup |

Override any property with an environment variable using Spring's `UPPER_SNAKE_CASE` convention, e.g. `PIERIA_DAEMON_PORT=9000`.

## Building and running

```bash
# Run locally (requires Ollama on localhost:11434)
./gradlew :daemon:bootRun

# Build the executable jar
./gradlew :daemon:bootJar
# → modules/daemon/build/libs/pieria.jar

# Build a GraalVM native executable (requires GraalVM 25+)
./gradlew :daemon:nativeCompile
# → modules/daemon/build/native/nativeCompile/pieria-daemon

# Run the jar directly
java -jar modules/daemon/build/libs/pieria.jar
```

## Testing

```bash
./gradlew :daemon:test
```

Tests use an embedded SQLite store and a `FakeModelGateway` (deterministic, no Ollama required). The `GatewayDaemonSmokeTests` integration test boots the full daemon on a random port and exercises the complete round trip — remember → list → recall → forget — against the real store. The `ServiceScriptTests` verify the packaging shell scripts (launchd, systemd, Windows service) produce the expected output without a running daemon.
