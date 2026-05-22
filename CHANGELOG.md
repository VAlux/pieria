# Changelog

All notable changes to Pieria are documented here.

---

## [Unreleased] — Phase 1: Embedded Walking Skeleton

### Added

**Build & configuration**
- Removed `spring-boot-starter-data-jpa`, the Hibernate ORM plugin, and related test starters from `build.gradle.kts`; the ORM fought SQLite's FTS5/`json1`/`INSERT OR IGNORE`/WAL features that will dominate Phase 3.
- Swapped `spring-boot-starter-data-jdbc` → `spring-boot-starter-jdbc` to avoid Spring Data JDBC's Dialect resolution requirement (SQLite has no dialect), while retaining `JdbcClient`.
- Added `spring-ai-starter-model-ollama` (chat + embedding; two-tier model config per SPEC §4.1).
- `PieriaProperties` — `@ConfigurationProperties(prefix="pieria")` record binding daemon, db, Ollama, and two-tier model knobs (`chatSmall`, `chatLarge`, `embedding`, `embeddingDimension`).
- `DataSourceConfig` — programmatic Hikari `DataSource` from `pieria.db.path`; creates the parent directory on startup; sets `PRAGMA journal_mode=WAL` via `connectionInitSql`.
- Full `application.properties`: daemon bind (`127.0.0.1:8077`), SQLite path, Ollama base URL, default model names (`llama3.2:3b` small, `llama3.1:8b` large, `mxbai-embed-large` embedding, dimension 1024), `spring.ai.ollama.init.pull-model-strategy=never`.

**Database schema**
- `db/migration/V1__init.sql` — all four SPEC §5.2 tables with every column created up front so Phases 2–3 add behavior without schema churn:
  - `profiles` (id, name, created_at)
  - `messages` (content-addressed id, profile_id, session_id, role, content, created_at)
  - `memories` (content-addressed id, profile_id, session_id, type, content, topic_key, supersedes, superseded, payload, embed_text, created_at)
  - `vectorization_outbox` (memory_id, enqueued_at, attempts)
  - Partial indexes `idx_mem_profile_key` and `idx_mem_profile_type` (filtered on `superseded = 0`)
  - FTS5 and sqlite-vec virtual tables deferred to Phase 3

**Domain**
- `MemoryType` enum (`FACT`, `EVENT`, `INSTRUCTION`, `TASK`) with `wire()` / `fromWire()` helpers.
- Records: `Profile`, `Message`, `Memory`, `RecallCandidate`, `ExportRow`.
- `ContentId` — content-addressed ID helper: `SHA-256(parts...)` truncated to 128 bits, hex-encoded; makes ingestion idempotent via `INSERT OR IGNORE`.
- `NotFoundException` — 404-mapped runtime exception with `profile(name)` / `memory(id)` factories.

**Storage**
- `MemoryStore` interface — the single persistence seam behind both backends (SPEC §5.4): `getOrCreateProfile`, `findProfile`, `insertMessages`, `insertMemory`, `listMemories`, `forgetMemory`, `exportProfile`, `findRecallCandidates`. Shaped so Phase 3 can add FTS/vector/HyDE channels and RRF fusion without changing callers.
- `SqliteMemoryStore` — `@Repository` implementing `MemoryStore` via `JdbcClient`:
  - Content-addressed IDs via `ContentId`; `INSERT OR IGNORE` for idempotent ingestion.
  - `forgetMemory` marks `superseded = 1` (logical delete, never physical).
  - `findRecallCandidates` — Phase 1 lexical retrieval: term-in-content LIKE scoring + message-session surface; shaped for Phase 3 multi-channel swap-in.
  - Write methods annotated `@Transactional`.
- `SqlSelect` — small, reflection-free fluent SELECT builder over `JdbcClient` (`from`, `where`, `and`, `andIf`, `orderBy`, `limit`, `map`, `findOne`). Removes dynamic `StringBuilder`/params juggling from read methods with no new dependency; stays GraalVM native-image safe.

**Model gateway**
- `ModelGateway` interface — `extractMemories`, `synthesizeRecall`, `embed`.
- `ModelUnavailableException` — 503-mapped runtime exception; implementations must never leak provider host/secret in the message.
- `ModelGatewayConfig` — `@Configuration` building two qualified `ChatClient` beans (`extractionChatClient` small, `synthesisChatClient` large) from the autoconfigured `OllamaChatModel` + `PieriaProperties`.
- `OllamaModelGateway` — `@Component implements ModelGateway`; constructor-injects the two qualified clients and the autoconfigured `EmbeddingModel`; wraps provider failures in `ModelUnavailableException`.

**API & services**
- `IngestionService` — `ingest` (raw messages → `extractMemories` → store) and `remember` (explicit single memory, no model call).
- `RetrievalService` — `recall` → `findRecallCandidates` → `synthesizeRecall` → `RecallResult`.
- `ProfileController` — all six profile-scoped endpoints under `/v1/profiles/{name}`:

  | Method | Path | Purpose |
  |--------|------|---------|
  | POST | `/ingest` | Bulk-extract memories from a conversation |
  | POST | `/memories` | Store a single memory explicitly (201) |
  | POST | `/recall` | Retrieval + synthesized answer |
  | GET | `/memories` | List active memories (type/session filters) |
  | DELETE | `/memories/{id}` | Forget a memory (logical delete, 204) |
  | GET | `/export` | Export all memories as NDJSON |

- Request/response DTOs with Jakarta Bean Validation (`@NotBlank`, `@NotEmpty`, `@Valid`).
- `GlobalExceptionHandler` (`@RestControllerAdvice`): validation/`IllegalArgumentException` → 400, `NotFoundException` → 404, `ModelUnavailableException` → 503; sanitized `{error, message}` bodies with no filesystem paths or provider secrets.
- NDJSON export uses `tools.jackson.databind.ObjectMapper` (Spring Boot 4 / Jackson 3 namespace).

**Tests**
- `SqliteMemoryStoreTests` — 10 fast integration tests against a temp-file SQLite DB (Flyway + WAL, no Spring context): WAL active, idempotent messages/memories, type/session filters, logical forget, export including superseded rows, lexical recall.
- `FakeModelGateway` — deterministic test double (no network): fixed extraction, deterministic synthesis, 1024-float embed, throw-mode for 503 testing.
- `FakeModelGatewayTests` — 6 tests covering all fake behaviors.
- `ProfileApiTests` — 7 `@WebMvcTest` slice tests covering all six endpoints, 400/404/503 error paths; no Ollama/network required.
- `PieriaApplicationTests` — full `@SpringBootTest` context load (`@ActiveProfiles("test")`).
- `src/test/resources/application-test.properties` — layered test overrides: `pieria.db.path=${java.io.tmpdir}/pieria-test/${random.uuid}.db` (throwaway DB per run, prevents Flyway checksum drift during development), `spring.ai.ollama.init.pull-model-strategy=never`.

### Changed

- `PieriaApplication` — added `@ConfigurationPropertiesScan`.
- `build.gradle.kts` — replaced JPA/Hibernate with plain JDBC and Ollama AI starter; removed Spring Data JDBC dialect machinery.

### Fixed

- Spring Boot 4 ships Jackson 3 (`tools.jackson`), not Jackson 2 (`com.fasterxml.jackson`). `ProfileController`'s NDJSON serialization switched to `tools.jackson.databind.ObjectMapper` so an autoconfigured bean is resolved in the full context.
- Spring Data JDBC's `DialectResolver` threw `NoDialectException` for SQLite; resolved by removing `data-jdbc` in favour of plain `jdbc`.
- `OllamaModelGateway` had a typo in `@Qualifier("sysnthesisChatClient")` (transposed letters); corrected to `"synthesisChatClient"`.
- Flyway checksum validation failure on re-runs after a migration reformat: test profile now uses `${random.uuid}` in the DB path for a guaranteed fresh database per context load.

### Architecture decisions recorded

- **JdbcClient over JPA/ORM**: SQLite-specific features (FTS5, sqlite-vec, `json1`, `INSERT OR IGNORE`, WAL) are not expressible in JPA Criteria; JPA's native-image overhead is the heaviest of the available options. `JdbcClient` with hand-written SQL is reflection-free and natively safe.
- **JdbcClient `SqlSelect` helper over jOOQ**: jOOQ would have added build-time codegen complexity, struggles with SQLite virtual tables (`CREATE VIRTUAL TABLE ... USING fts5/vec0`), and Phase 3's FTS5/vec/RRF queries are raw SQL regardless. The thin `SqlSelect` builder recovers the readability benefit with no new dependency and no native-image risk.
- **Two-tier model config from Phase 1**: `chatSmall`/`chatLarge`/`embedding` knobs defined now (SPEC §4.1) so Phases 2–3 do not reshape configuration.
- **All SPEC §5.2 schema columns created in V1**: defers no migration churn to Phase 2.
- **`forgetMemory` is a logical delete**: sets `superseded = 1`; the row is retained for the export/provenance path (SPEC §13) and the version chain (SPEC §5.6).
