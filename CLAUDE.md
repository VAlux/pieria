# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**Pieria** is a local-first persistent memory layer for AI agents. It runs as a background daemon, exposes a REST API on localhost, and lets AI coding tools (Claude Code, OpenCode, Codex, etc.) share a single memory store via MCP. See `docs/SPEC.md` for the full specification and `docs/PLAN.md` for the phased implementation plan.

The current state is an **early scaffold** — Spring Boot entry point exists, no domain code yet. Implementation follows a six-phase plan; always check `docs/PLAN.md` for the current phase's scope before adding code.

## Build and test commands

```bash
./gradlew test          # run the full test suite (required before any commit)
./gradlew build         # compile + test + assemble
./gradlew bootRun       # run the application locally
./gradlew bootBuildImage  # build a container image
./gradlew nativeCompile   # GraalVM native executable (requires GraalVM 25+)
./gradlew nativeTest      # run tests in native image
```

## Stack

- **Java 25**, Spring Boot 4.0.6, Gradle Kotlin DSL
- **Spring AI 2.0.0-M6** for model orchestration (Ollama default; Anthropic/OpenAI opt-in)
- **SQLite + sqlite-vec + FTS5** for the embedded backend (default)
- **PostgreSQL + pgvector** for server/multi-user mode (Phase 6, opt-in)
- **Flyway** for migrations; **Testcontainers** for Postgres integration tests
- **JUnit 5** via `useJUnitPlatform()`

## Architecture

### Core abstractions (to be implemented)

- `MemoryStore` — single interface behind both storage backends (SQLite and Postgres). All retrieval channels and ingestion writes are defined against this interface. This is what makes the local→server transition a backend swap.
- `ModelGateway` — provider-agnostic chat + embedding; backed by Spring AI. Two tiers: small/fast model for structured stages (extract/verify/classify), large model for synthesis only.
- `IngestionService` — write path: content-addressed IDs → parallel extraction → verification → classification → supersession → store → async vectorization outbox.
- `RetrievalService` — read path: query analysis + embedding → five parallel channels (FTS, exact key, raw message FTS, direct vector, HyDE vector) → RRF fusion → synthesis.
- `VectorizationWorker` — virtual-thread worker that drains the outbox and writes embeddings.

### Key design constraints

- **Single daemon, single writer**: the daemon is the only writer to the embedded SQLite store. Harnesses connect through thin MCP stdio shims. Never open the embedded DB directly from a harness.
- **Content-addressed IDs**: message and memory IDs are `SHA-256(sessionId + role + content)` truncated to 128 bits, making ingest idempotent (insert-or-ignore on conflict).
- **Supersession, not deletion**: when a new keyed `fact` or `instruction` shares a `topic_key` with an existing memory, mark the old one superseded and point the new row's `supersedes` at it. Remove the old vector in the same transaction.
- **Tasks excluded from vector index**: `task` memories are discoverable via FTS/listing but not embedded, to keep the index lean.
- **Temporal arithmetic in Java, not the model**: date math and duration calculations are handled deterministically in code and injected into synthesis prompts as pre-computed facts.

### REST API surface (`/v1/profiles/{name}`)

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/ingest` | Bulk-extract memories from a conversation |
| POST | `/memories` | Store a single memory explicitly |
| POST | `/recall` | Run retrieval, return synthesized answer |
| GET | `/memories` | List memories (filter by type/session) |
| DELETE | `/memories/{id}` | Forget a memory |
| GET | `/export` | Export all memories (NDJSON) |

### Package structure (use as the codebase grows)

Keep all code under `dev.alvo.pieria`. Suggested sub-packages: `api` (controllers/DTOs), `domain` (records/enums), `storage` (MemoryStore + backend impls), `ingestion`, `retrieval`, `model` (ModelGateway), `mcp` (shim).

## Testing conventions

- Test class names use `*Tests` suffix (e.g. `PieriaApplicationTests`).
- Use `@SpringBootTest` only when a full context is needed; prefer narrower slice or unit tests.
- Model gateway dependencies must use fakes/stubs in tests — CI does not require Ollama or network access.
- Postgres integration tests use Testcontainers and belong in Phase 6 only.
- `./gradlew test` must pass before every commit.

## Configuration

The daemon binds to `127.0.0.1` by default and must never bind a public interface in local mode. Key properties to add in `application.properties` as implementation progresses: database path, daemon host/port, Ollama base URL, chat model name, embedding model name, and embedding dimension (fixes the `FLOAT[n]` column width — decide this once and avoid re-embedding later).
