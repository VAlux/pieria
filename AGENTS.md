# AGENTS.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

**Pieria** is a local-first persistent memory layer for AI agents. It runs as a background daemon, exposes a REST API on localhost, and lets AI coding tools (Claude Code, OpenCode, Codex, etc.) share a single memory store via MCP. See `docs/SPEC.md` for the full specification and `docs/PLAN.md` for the phased implementation plan.

The current state has the local daemon, ingestion/retrieval pipeline, MCP stdio gateway, evaluation harness, and shared DTOs split across four Gradle modules under `modules/`. Implementation follows a six-phase plan; always check `docs/PLAN.md` for the current phase's scope before adding code.

## Build and test commands

```bash
./gradlew test                    # run the full test suite (required before any commit)
./gradlew build                   # compile + test + assemble
./gradlew :daemon:nativeCompile   # GraalVM daemon executable (requires GraalVM 25+)
./gradlew :gateway:nativeCompile  # GraalVM gateway executable (requires GraalVM 25+)
./gradlew :cli:nativeCompile      # GraalVM CLI executable (requires GraalVM 25+)
./gradlew :daemon:nativeDist      # assemble full native distribution (daemon + gateway + cli + harness)
./gradlew :daemon:deployLocal     # build native dist and sync into $PIERIA_HOME
```

**Never run `nativeCompile`, `nativeDist`, or `deployLocal` on your own.** They are slow and `deployLocal` overwrites the user's installed binaries. Always ask the user to run them, or for explicit permission first. Verify code changes with `./gradlew test` and plain `compileJava` instead.

## Stack

- **Java 25**, Spring Boot 4.0.6, Gradle Kotlin DSL
- **Spring AI 2.0.0-M6** for model orchestration (Ollama default; Anthropic/OpenAI opt-in)
- **SQLite + sqlite-vec + FTS5** for the embedded backend (default)
- **PostgreSQL + pgvector** for server/multi-user mode (Phase 6, opt-in)
- **Flyway** for migrations; **Testcontainers** for Postgres integration tests
- **JUnit 5** via `useJUnitPlatform()`

## Architecture

### Core abstractions

- `MemoryStore` — single interface behind both storage backends (SQLite and Postgres). All retrieval channels and ingestion writes are defined against this interface. This is what makes the local→server transition a backend swap.
- `ModelGateway` — provider-agnostic chat + embedding; backed by Spring AI. Two tiers: small/fast model for structured stages (extract/verify/classify), large model for synthesis only.
- `IngestionService` — write path: content-addressed IDs → parallel unified extraction (one call per chunk emits candidates with their classification) → grounding pre-filter + model verification of suspects → supersession → store → async vectorization outbox.
- `RetrievalService` — read path: query analysis + embedding → five parallel channels (FTS, exact key, raw message FTS, direct vector, HyDE vector) → RRF fusion → synthesis.
- `VectorizationWorker` — virtual-thread worker that drains the outbox and writes embeddings.

### Key design constraints

- **Single daemon, single writer**: the daemon is the only writer to the embedded SQLite store. Harnesses connect through thin MCP stdio gateways. Never open the embedded DB directly from a harness.
- **Content-addressed IDs**: message and memory IDs include `profileId` in the SHA-256 input and are truncated to 128 bits, making ingest idempotent within a profile while identical content can coexist across profiles. Legacy unscoped IDs remain readable and are reused only by their owning profile.
- **Supersession, not deletion**: when a new keyed `fact` or `instruction` shares a `topic_key` with an existing memory, mark the old one superseded and point the new row's `supersedes` at it. Remove the old vector in the same transaction. **Supersession is ordered by when a claim was *stated*, not when it was stored** (`MemoryTimes.knowledgeTime`): a replayed or back-filled transcript arrives after facts that are newer than it, and ordering by store time would let it silently replace current knowledge with older knowledge. A memory that arrives already stale is stored `superseded = 1` — kept as history, never embedded, and the active row is left alone. With no stated time on either side there is no evidence of staleness, so the original store-order behaviour applies.
- **Tasks excluded from vector index**: `task` memories are discoverable via FTS/listing but not embedded, to keep the index lean.
- **Temporal arithmetic in Java, not the model**: date math and duration calculations are handled deterministically in code and injected into synthesis prompts as pre-computed facts. This applies at both ends — `TranscriptNormalizer` resolves relative dates out of a transcript at ingest, and `TemporalExtractor` resolves whatever survived into the stored memories at recall. Both share `RelativeDates` so a memory and an answer quoting it cannot disagree. Ingestion stamps `stated_at` (when the source turn was spoken) into every memory's payload, and that is the anchor retrieval resolves against — never `Memory.createdAt`, which is *store* time and would resolve a back-filled transcript to the ingest date. `occurred_at` wins over `stated_at` when present, because when a thing happened beats when it was mentioned; the speaking time is never written to `occurred_at`, which would corrupt the event-date arithmetic that already reads it. A residual reference with no trustworthy anchor is *reported as unresolvable* rather than guessed at, because the alternative — synthesis combining a stray "next month" with a date from a neighbouring memory — was observed producing confident, wrong dates.
- **Ingest time is not conversation time**: `TranscriptNormalizer` resolves relative dates against *when the turn was spoken* — `Message.createdAt` when set, else the request's `occurredAt`, else the daemon clock. Every resolved reference is **replaced at the granularity the speaker used** — "yesterday" → an ISO date, `"next month"` → `"June 2023"`, `"last week"` → `"the week of 2023-05-15"` — so nothing is invented. Never *annotate* a date alongside the original phrase: a parenthetical is detachable, and extraction was observed re-attaching `"(June 2023)"` to a neighbouring clause, stranding "next month" with no anchor and leading synthesis to do its own arithmetic on the stray date. Genuinely fuzzy references (seasons, "a while back") are left for the model. A replayed or back-filled transcript must send those timestamps (`IngestRequest.occurredAt`, or per-turn `MessageDto.timestamp` for multi-session transcripts), or its relative dates silently resolve to the ingest wall clock. Message timestamps are excluded from the content-addressed id, so supplying them keeps ingest idempotent.

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

Keep all code under `dev.alvo.pieria`, with module boundaries enforced by Gradle:

All modules live under `modules/`:
- `shared`: HTTP request/response DTOs, config file model/loader, daemon HTTP clients, `ProfileResolver`, and generic utilities (`dev.alvo.pieria.tools`). Every other module depends on it.
- `daemon`: REST controllers, domain, storage, ingestion, retrieval, and model gateway.
- `gateway`: stdio MCP tools and the HTTP client that forwards to the daemon.
- `eval`: the LoCoMo benchmark harness — dataset adapter, runner, and JSON/HTML report writers. Run it
  with `./gradlew :eval:locomo --args="--help"`; see `modules/eval/README.md`.

## Utility code: no duplication across modules

Cross-module utility code (strings, collections, hashing, OS/platform detection, path resolution,
file I/O, retry/backoff, time formatting) belongs in `shared`, not reimplemented per module. See
`.claude/rules/utility-code-placement.md` for the full rule.

## Testing conventions

- Test class names use `*Tests` suffix (e.g. `PieriaApplicationTests`).
- Use `@SpringBootTest` only when a full context is needed; prefer narrower slice or unit tests.
- Model gateway dependencies must use fakes/stubs in tests — CI does not require Ollama or network access.
- Don't add test seams to production code (e.g. public/injectable fields or `null`-fallback overrides that exist only so a test can swap an implementation). Test through the real public surface instead — for CLI commands, point `--daemon-url` (or similar) at a throwaway localhost HTTP stub rather than injecting a fake client.
- Postgres integration tests use Testcontainers and belong in Phase 6 only.
- `./gradlew test` must pass before every commit.

## Configuration

The daemon binds to `127.0.0.1` by default and must never bind a public interface in local mode. Key properties to add in `application.properties` as implementation progresses: database path, daemon host/port, Ollama base URL, chat model name, embedding model name, and embedding dimension (fixes the `FLOAT[n]` column width — decide this once and avoid re-embedding later).
