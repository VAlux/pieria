# Phase 1 - Embedded Walking Skeleton

## Objective

Build the first runnable Pieria daemon on the embedded SQLite backend. This phase should prove the core loop end to end: receive profile-scoped REST requests, persist messages and memories, call Spring AI through a provider-neutral gateway, and return a synthesized recall answer.

## Scope

- Local-only Spring Boot application using Java 25, Spring Boot 4.0.6, Gradle Kotlin DSL, and Spring AI 2.0.0-M6.
- Embedded SQLite as the only active storage backend.
- Minimal but stable domain contracts and REST DTOs.
- Naive model-assisted `ingest` and `recall` paths.
- No MCP shim, native packaging, Postgres backend, or full retrieval fusion yet.

## Implementation Sequence

1. Add the foundational package layout.
   - `dev.alvo.pieria.api` for REST controllers, DTOs, validation, and error mapping.
   - `dev.alvo.pieria.config` for configuration properties and bean wiring.
   - `dev.alvo.pieria.domain` for records, enums, and IDs.
   - `dev.alvo.pieria.storage` for `MemoryStore` and SQLite implementation.
   - `dev.alvo.pieria.model` for `ModelGateway` and Spring AI adapters.
   - `dev.alvo.pieria.ingestion` and `dev.alvo.pieria.retrieval` for service boundaries.

2. Configure the embedded database.
   - Add application properties for database path, daemon host, daemon port, Ollama base URL, and embedding dimension.
   - Configure the **two model tiers** behind `ModelGateway` from the start (per SPEC §4.1): a small/fast chat model (extraction/verification/classification/query analysis in later phases) and a large model used for synthesis only, plus a separate embedding model. Phase 1 may point both chat tiers at the same default Ollama model, but the two configuration knobs and gateway methods must already exist so Phases 2-3 do not reshape config.
   - Use Flyway migrations under `src/main/resources/db/migration`.
   - Create `profiles`, `messages`, `memories`, and `vectorization_outbox`. The `memories` table includes all SPEC §5.2 columns now — `type`, `content`, `topic_key`, `supersedes`, `superseded`, `payload`, `embed_text`, `created_at` — even though `topic_key`, `supersedes`/`superseded`, and `embed_text` are not populated until Phase 2, so later phases need no schema migration churn.
   - Enable SQLite WAL mode at startup through the datasource or a startup initializer.
   - Keep vector and FTS-specific schema out of this phase unless the dependency is already validated.

3. Define domain and API models.
   - Add `MemoryType` values: `FACT`, `EVENT`, `INSTRUCTION`, `TASK`.
   - Add records for `Profile`, `Message`, `Memory`, `RecallCandidate`, and export rows.
   - Add request/response DTOs for all public endpoints:
     - `POST /v1/profiles/{name}/ingest`
     - `POST /v1/profiles/{name}/memories`
     - `POST /v1/profiles/{name}/recall`
     - `GET /v1/profiles/{name}/memories`
     - `DELETE /v1/profiles/{name}/memories/{id}`
     - `GET /v1/profiles/{name}/export`
   - Resolve profile names to internal profile IDs only inside the API/service boundary.

4. Implement the `MemoryStore` contract.
   - `getOrCreateProfile(name)`.
   - `insertMessages(profileId, sessionId, messages)`.
   - `insertMemory(profileId, memory)`.
   - `listMemories(profileId, filters)`.
   - `forgetMemory(profileId, memoryId)`.
   - `exportProfile(profileId)`.
   - `findRecallCandidates(profileId, query, limit)` using simple keyed and `LIKE` lookup for now.
   - Use transactions for write operations and return deterministic results for tests.

5. Implement explicit memory operations.
   - `remember` stores one user-provided memory without requiring an extraction model call.
   - `list` supports type and session filters.
   - `forget` marks a memory inactive or superseded rather than physically deleting it.
   - `export` returns newline-delimited JSON-friendly rows that can later become the migration format.

6. Implement naive ingestion.
   - Store the raw conversation messages first.
   - Send a single structured prompt to the configured Spring AI chat model.
   - Ask the model to return candidate memories with type, content, optional topic key, and optional payload.
   - Validate and store accepted memories.
   - If the model is unavailable, fail clearly and leave already-stored messages in a consistent state only if the transaction strategy deliberately allows it.

7. Implement initial recall.
   - Retrieve candidates from the embedded store using topic key, type, content, and message text where available.
   - Build a synthesis prompt containing the original query and top candidates.
   - Call `ModelGateway.synthesizeRecall(...)`.
   - Return answer text plus memory references used for synthesis.
   - Keep the retrieval interfaces shaped so Phase 3 can add FTS, vector, HyDE, and fusion without rewriting the controller.

8. Add application-level error handling.
   - Return validation errors as `400`.
   - Return missing memory/profile-scoped resource errors as `404`.
   - Return model availability failures as `503`.
   - Avoid leaking local filesystem paths or provider secrets in error bodies.

## Tests

- Unit tests for DTO validation, `MemoryType` mapping, and profile name validation.
- Storage integration tests for Flyway migrations, WAL initialization, profile creation, `remember`, `list`, `forget`, and `export`.
- API tests for all six `/v1/profiles/{name}` endpoints using mocked or fake `ModelGateway`.
- Model gateway tests should use fakes or Spring AI test doubles; CI must not require Ollama or network access.
- Run `./gradlew test`.

## Acceptance Criteria

- The application starts with a local SQLite database path from configuration.
- Every public REST endpoint exists and is profile-scoped.
- A user can remember a memory, list it, recall it, forget it, and export it.
- Ingest can turn a small transcript into stored memories through one configurable Spring AI chat call.
- Recall produces a synthesized answer from stored candidates.
- `docs/PLAN.md` remains unchanged.

## Risks And Follow-Ups

- SQLite vector support and FTS are intentionally deferred unless they are trivial to validate.
- The naive model prompt is a temporary bridge; Phase 2 replaces it with extraction, verification, classification, and content-addressed IDs.
- The simple recall query is not expected to be high quality; Phase 3 replaces it with multi-channel retrieval.
