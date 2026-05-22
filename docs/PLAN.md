# Pieria Implementation Plan

## Summary

Implement Pieria as a local-first memory daemon using the current scaffold stack: Java 25, Spring Boot 4.0.6, Gradle Kotlin DSL, and Spring AI. Preserve the `SPEC.md` architecture and six-phase roadmap, but adapt model orchestration to Spring AI rather than LangChain4j. Default model providers remain configurable through application properties, with Ollama as the local-first default and concrete model names selected during Phase 1 setup.

## Public Interfaces

- Add localhost REST API under `/v1/profiles/{name}`:
  - `POST /ingest`
  - `POST /memories`
  - `POST /recall`
  - `GET /memories`
  - `DELETE /memories/{id}`
  - `GET /export`
- Define core Java contracts:
  - `MemoryStore` for profile, message, memory, outbox, FTS, vector, and export operations.
  - `ModelGateway` backed by Spring AI chat and embedding clients.
  - `IngestionService`, `RetrievalService`, and `VectorizationWorker`.
- Use profile names at the API boundary; resolve to internal profile IDs in storage.

## Phased Implementation

### Phase 1: Embedded Walking Skeleton

- Configure SQLite as the default local backend using `sqlite-jdbc`, Flyway migrations, WAL mode, profiles, messages, memories, and vectorization outbox tables.
- Add initial domain records/enums for profiles, messages, memory types, memories, recall candidates, and API DTOs.
- Implement `remember`, `list`, `forget`, `export`, and a naive `ingest` path that stores messages and creates memories through one configurable Spring AI chat call.
- Implement initial `recall` using embedded memory lookup plus model synthesis; if vector support is not ready, use FTS/keyed lookup behind the same retrieval interfaces.
- Add application configuration for database path, daemon host/port, Ollama base URL, chat model, embedding model, and embedding dimension.

### Phase 2: Full Ingestion Pipeline

- Make ingestion idempotent with content-addressed message and memory IDs.
- Add chunking, parallel extraction, optional detail extraction for longer sessions, verification, classification, topic key generation, and interrogative query generation.
- Implement supersession for keyed `fact` and `instruction` memories in one transaction.
- Enqueue all vector-eligible memories except `task`.
- Add virtual-thread based async vectorization worker using Spring AI embeddings.

### Phase 3: Full Retrieval Pipeline

- Add FTS5 tables/triggers for memories and messages.
- Add sqlite vector search once the embedded vector dependency is validated.
- Implement query analysis producing topic keys, FTS terms, synonyms, and HyDE statements.
- Run five retrieval channels in parallel: memory FTS, exact topic key, raw message FTS, direct vector, and HyDE vector.
- Implement weighted Reciprocal Rank Fusion with recency tie-breaking.
- Add deterministic temporal extraction/date arithmetic before final synthesis.

### Phase 4: Daemon, MCP Shim, and Local Harness Integration

- Harden the Spring Boot app as the localhost daemon: bind to `127.0.0.1` by default, expose `/healthz`, and serialize embedded writes through service transactions.
- Add a thin MCP stdio shim that exposes `recall`, `remember`, `list`, and `forget`, forwarding to the daemon REST API.
- Add profile mapping from env/config, defaulting to git remote or project directory name.
- Provide Claude Code installation assets first: MCP registration plus session-start, pre-compact, and stop hooks.
- Document equivalent OpenCode and Codex configuration snippets.

### Phase 5: Packaging, First Run, and Evaluation

- Configure native-image packaging for Spring Boot 4/GraalVM and keep JVM packaging as fallback.
- Add OS service installation scripts for launchd first, then systemd and Windows service.
- Implement first-run initialization: app data directory creation, migrations, model availability checks, and setup output.
- Add local logs and per-stage latency/token metrics without remote telemetry.
- Build an evaluation harness with fixture-based local tests first, then adapters for LoCoMo and LongMemEval.

### Phase 6: Server Mode

- Add a PostgreSQL storage implementation behind `MemoryStore`.
- Use native JSONB, `tsvector`, pgvector, generated FTS columns, GIN indexes, HNSW vector index, and partial indexes for active memories.
- Add API authentication and profile isolation using Row-Level Security or equivalent session-scoped profile enforcement.
- Implement import from local NDJSON export and re-embedding into the server backend.
- Keep local SQLite mode as the default and server mode opt-in by configuration.

## Test Plan

- Unit tests for ID generation, chunking, classification mapping, supersession, RRF scoring, temporal arithmetic, and DTO validation.
- Storage integration tests for SQLite migrations, profile resolution, idempotent ingest, forget/list/export, FTS sync, and outbox behavior.
- API tests with Spring Boot test support for all `/v1/profiles/{name}` endpoints.
- Model-gateway tests using fakes/stubs so CI does not require Ollama or network access.
- Retrieval tests with deterministic fixtures covering keyed lookup, FTS, raw message fallback, vector channel stubs, HyDE channel stubs, and synthesis prompt inputs.
- Postgres Testcontainers tests added in Phase 6 only.
- Run `./gradlew test` as the required verification command for each phase.

## Assumptions

- Use the current scaffold stack: Java 25, Spring Boot 4.0.6, Spring AI 2.0.0-M6, Gradle Kotlin DSL.
- Keep Ollama as the default provider, but make model names and embedding dimension configurable rather than hard-coded in the plan.
- Implement local SQLite mode first; server mode is not allowed to complicate Phase 1-5 code paths beyond the `MemoryStore` abstraction.
- Hosted model providers are optional configuration, not required for a working local installation.
- `task` memories are searchable through FTS/listing but excluded from vector indexing.
