# Daemon

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
├── model/               ModelGateway interface, OpenAiModelGateway
├── retrieval/
│   ├── channel/         Retrieval channels: FTS memory, FTS message, exact key, direct vector, HyDE vector, graph
│   ├── RetrievalService.java
│   ├── ReciprocalRankFusion.java
│   ├── DeterministicQueryAnalyzer.java
│   └── TemporalExtractor.java
└── storage/             MemoryStore interface, SqliteMemoryStore, SqliteVectorIndex
```

## Key abstractions

**`MemoryStore`** — the single persistence seam. All ingestion writes and retrieval reads go through this interface. Swapping it for a Postgres implementation (Phase 6) requires no changes to the ingestion or retrieval services.

**`ModelGateway`** — provider-agnostic access to chat and embedding models. Two tiers: a small/fast model for structured stages (extraction, verification, classification, query analysis) and a large model for final synthesis only. Default implementation: `OpenAiModelGateway`.

**`IngestionService`** — the write path. Given a list of messages and a session ID: generates content-addressed IDs → runs full + detail extraction in parallel (structured concurrency) → verifies each candidate → classifies and keys → handles supersession → stores → enqueues vectorization.

**`RetrievalService`** — the read path. Given a recall query: analyses the query and embeds it in parallel → fans out to the five primary channels in parallel (structured concurrency) → runs the graph channel as a second wave seeded from the first wave's hits → fuses everything with RRF → synthesizes a natural-language answer.

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
| `pieria.provider.base-url` | `http://localhost:11434` | Provider API root, **without** the `/v1` suffix (the client appends it) |
| `pieria.provider.api-key` | `ollama` | Bearer token sent to the provider; local providers ignore it, so any non-blank placeholder works |
| `pieria.provider.name` | `ollama` | Display-only label surfaced on `/pieria-status` and `/pieria-health` |
| `pieria.model.extraction-model` | `qwen3:8b` | Small/fast model for structured stages (extract, verify, classify, analyze) |
| `pieria.model.synthesis-model` | `gemma3:12b` | Large model for synthesis |
| `pieria.model.embedding` | `mxbai-embed-large` | Embedding model |
| `pieria.model.embedding-dimension` | `1024` | Fixes the `FLOAT[n]` vector column width — set once |
| `pieria.retrieval.vector-enabled` | `true` | Disable if `sqlite-vec` is not available |
| `pieria.first-run.enabled` | `true` | Run first-run setup on startup |

Override any property with an environment variable using Spring's `UPPER_SNAKE_CASE` convention, e.g. `PIERIA_DAEMON_PORT=9000`.

### Graph retrieval channel

The entity-relation graph adds a **second retrieval wave**. The five primary channels run first; the graph channel then traverses the relationship graph — seeded from the entities named in the query **and** the entities attached to the top candidates the primary channels surfaced — and its hits are fused into the same weighted Reciprocal Rank Fusion. Graph traversal at recall time is indexed SQL only (the entity/relation extraction happens at ingest), so the second wave adds little latency beyond not overlapping the first wave.

All graph parameters live under `pieria.retrieval.*` in `application.properties`:

| Property | Default | Description |
|---|---|---|
| `pieria.retrieval.weight-graph` | `1.0` | RRF fusion weight for the graph channel. **`0` disables the channel entirely** — the second wave is skipped, so there is no traversal cost (use this to A/B the graph's contribution). A primary-tier signal: below `weight-exact-key` (`3.0`), comparable to the FTS/vector channels (`1.0`). |
| `pieria.retrieval.graph-depth` | `2` | Neighborhood expansion depth, in hops, from the seed entities. `1` = only entities directly linked to a seed; `2` = also their neighbors. Higher values widen recall of indirectly-connected memories but risk topic drift; keep small. |
| `pieria.retrieval.graph-fanout` | `20` | Maximum number of *newly-discovered* entities followed per hop (most-recent edges first). Bounds the combinatorial blow-up of expansion on densely-connected nodes. |
| `pieria.retrieval.graph-seed-limit` | `8` | Maximum seed entities taken from each source — the query entities, and the wave-1 candidate memories — before expansion. Caps how wide the traversal starts. |

Two shared retrieval properties also bound the graph channel: `pieria.retrieval.channel-limit` caps how many memories the graph channel returns before fusion, and `pieria.retrieval.channel-timeout-ms` bounds the graph wave like any other channel. The graph channel is **non-critical** — if it times out or errors it is logged and contributes nothing, and recall still succeeds on the primary channels.

**Tuning guidance.** The defaults are deliberate placeholders; `weight-graph` in particular is best set from Phase 5 evaluation data rather than assumed. The graph helps most on relational / multi-hop queries ("what depends on the auth service?") and on sparse-but-connected facts, and less on simple lookups already covered by FTS/vector. Start by confirming the contribution with `weight-graph=0` vs `1.0`; raise `graph-depth` to `2`+ only if recall of indirectly-related memories is lacking and precision holds.

### Switching providers

The daemon talks to any provider that exposes an **OpenAI-compatible API** (`/v1/chat/completions`, `/v1/embeddings`, `/v1/models`). Switching providers only means changing the `pieria.provider.*` connection settings and the `pieria.model.*` model names — no code change. `pieria.provider.base-url` is always the API **root**; the daemon appends `/v1/...` itself.

**Ollama** (default) — local, no key required:

```properties
pieria.provider.base-url=http://localhost:11434
pieria.provider.api-key=ollama
pieria.provider.name=ollama
pieria.model.extraction-model=qwen3:8b
pieria.model.synthesis-model=gemma3:12b
pieria.model.embedding=mxbai-embed-large
```

**LM Studio** — local server (default port 1234); the key is ignored:

```properties
pieria.provider.base-url=http://localhost:1234
pieria.provider.api-key=lm-studio
pieria.provider.name=lmstudio
pieria.model.extraction-model=qwen2.5-7b-instruct
pieria.model.synthesis-model=qwen2.5-14b-instruct
pieria.model.embedding=text-embedding-nomic-embed-text-v1.5
```

**llama.cpp** — `llama-server` exposes `/v1`; it accepts any key:

```properties
pieria.provider.base-url=http://localhost:8080
pieria.provider.api-key=sk-no-key-required
pieria.provider.name=llamacpp
```

**OpenAI** — hosted; a real key is required. Mind the `embedding-dimension` (e.g. `text-embedding-3-small` is 1536) — it fixes the vector column width and must be set before the first ingest:

```properties
pieria.provider.base-url=https://api.openai.com
pieria.provider.api-key=${OPENAI_API_KEY}
pieria.provider.name=openai
pieria.model.extraction-model=gpt-4o-mini
pieria.model.synthesis-model=gpt-4o
pieria.model.embedding=text-embedding-3-small
pieria.model.embedding-dimension=1536
```

**Azure OpenAI** — hosted on Azure; set `pieria.provider.type=azure`. The `base-url` is your Azure **resource endpoint** (not an OpenAI-style root), and the `pieria.model.*` values are your Azure **deployment names**, not model IDs. The daemon flips the underlying Spring AI / OpenAI-SDK Azure switches for you, so no `spring.ai.*` config is needed:

```properties
pieria.provider.type=azure
pieria.provider.base-url=https://<resource>.openai.azure.com
pieria.provider.api-key=${AZURE_OPENAI_API_KEY}
pieria.provider.name=azure
pieria.provider.api-version=2024-10-21
pieria.model.extraction-model=<chat-deployment-name>
pieria.model.synthesis-model=<chat-deployment-name>
pieria.model.embedding=<embedding-deployment-name>
pieria.model.embedding-dimension=1536
```

`pieria.provider.api-version` is the Azure REST API version (used only in `azure` mode). As with OpenAI, set `embedding-dimension` to match your deployed embedding model and fix it before the first ingest. The extraction/synthesis deployments may point at the same or different Azure deployments.

Because every property is overridable via the environment, you can keep secrets out of the file: `PIERIA_PROVIDER_API_KEY=sk-…`.

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
