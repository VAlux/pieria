# Pieria — Technical Specification

**A local-first memory layer for AI agents.**

> *Pieria* — the region at the foot of Mount Olympus that was the mythic home of the
> Muses and the site of the Pierian spring, the classical metaphor for the source of
> knowledge and memory.

**Status:** Draft v0.3 (local-first)
**Last updated:** 2026-05-23

---

## 1. Overview

**Pieria** is a **local-first**, persistent memory layer for AI agents. You install it
once, run it as a background service, and every AI coding tool on your machine shares the
same memory. Pieria extracts durable knowledge from agent conversations, stores it as
structured, deduplicated memories, and retrieves the relevant subset on demand — without
forcing the agent to keep everything in its context window.

It is inspired by Cloudflare's Agent Memory but designed to run entirely on the user's
own machine with no managed-service, network, or single-vendor dependency. The default
deployment needs nothing beyond the installed Pieria bundle and a local model runtime. A
**server mode** (multi-user, shared team memory) is supported as an opt-in, using the
same code with a different storage backend.

### 1.1 Goals

- **Local-first:** install, run, and use across the whole system with zero external
  services. No cloud account, no network round-trip required for a recall.
- **Shared across tools:** one memory store serves Claude Code, OpenCode, Codex, and any
  other MCP-capable harness on the machine simultaneously.
- Persist knowledge across agent sessions and survive process restarts.
- Extract facts, events, instructions, and tasks from raw conversation transcripts.
- Deduplicate and supersede memories so the store stays accurate as it grows.
- Retrieve relevant memories via fused multi-modal search (vector + full-text + keyed).
- Return a synthesized natural-language answer to a recall query.
- Be vendor-neutral and fully exportable; scale up to an optional shared server.

### 1.2 Non-goals

- General-purpose document/file search (this is context recall, not file retrieval).
- Acting as the agent harness itself — the service is a memory layer the harness calls.
- Distributed consensus or multi-master replication (local single-writer; server is
  single-primary).
- Replacing the agent's short-term/working context — only what survives compaction.

### 1.3 Core concepts

- **Profile** — a named memory store. Default convention is one profile per project/repo.
  The unit of organization (and, in server mode, of tenancy/isolation).
- **Memory** — a single extracted unit of knowledge, classified into one of four types.
- **Session** — a single conversation thread; memories carry a `sessionId` for provenance.
- **Daemon** — the long-running local process that owns the database and pipelines and
  binds an HTTP API to localhost. The single writer of the embedded store.
- **MCP shim** — a thin, per-harness stdio client that forwards tool calls to the daemon.
- **Ingest** — bulk path: extract memories from a conversation (called at compaction).
- **Remember** — store a single memory explicitly (direct model tool use).
- **Recall** — run the full retrieval pipeline and return a synthesized answer.
- **Forget** — mark a memory as no longer valid.

---

## 2. Deployment topologies

### 2.1 Local-first (default)

A single background **daemon** owns the embedded database and the pipelines, and binds an
HTTP API to `127.0.0.1`. Each harness launches a tiny **MCP stdio shim** that forwards to
the daemon. All harnesses on the machine therefore share one memory store.

```
  Agent harnesses ──► MCP stdio shims ──► Local daemon ──► Embedded store (SQLite + vec + FTS5)
  (any MCP harness)   (thin clients)      (binds 127.0.0.1)  └─► Local models (Ollama, default)
```

Why a single daemon rather than each harness opening the store directly: the embedded
database is **single-writer**, so concurrent direct access would deadlock on write locks.
Funnelling every harness through one daemon both solves that and delivers the goal —
memory learned in one tool is instantly available in another.

### 2.2 Server mode (opt-in, multi-user)

The same daemon runs on a shared host, swaps the embedded backend for **PostgreSQL +
pgvector**, enables multi-tenant isolation, and exposes the API (and MCP over streamable
HTTP) to multiple users. Used for shared team memory. No application code changes — only
the storage backend and tenancy configuration differ.

### 2.3 Operation

- Runs as an OS service: **launchd** (macOS), **systemd** (Linux), Windows service.
- Listens on a configurable localhost port; never binds a public interface in local mode.
- Single installable bundle (see §14): daemon + shim artifacts, preferably native images,
  with a JVM/`jpackage` fallback.

---

## 3. Architecture

The service is a storage hub with two pipelines: a **write path** (ingestion) that turns
raw conversation into structured memories, and a **read path** (retrieval) that fuses
several search strategies into one synthesized answer. The same pipelines run regardless
of deployment topology or storage backend.

```
  Conversation ──► Extract & verify ──► Classify & key ──┐
                  (parallel LLM passes)  (4 memory types)  │ write (ingest)
                                                           ▼
                                  ┌─────────────────────────────────┐
                                  │  Storage backend (pluggable)     │
                                  │  embedded: SQLite + vec + FTS5   │
                                  │  server:   Postgres + pgvector   │
                                  └─────────────────────────────────┘
                                                           │ read (recall)
                                                           ▼
  Query ──► Retrieve & fuse ──► Synthesize ──► Answer
           (FTS·keys·vector·HyDE → RRF)  (LLM writes answer)
```

### 3.1 Component responsibilities

| Component            | Responsibility                                                          |
|----------------------|-------------------------------------------------------------------------|
| Daemon               | Long-running process; owns DB + pipelines; binds localhost HTTP API.    |
| MCP shim             | Thin per-harness stdio client; forwards tool calls to the daemon.       |
| API layer            | REST endpoints; request validation; profile resolution.                |
| Ingestion service    | ID generation, extraction, verification, classification, supersession.  |
| Retrieval service    | Query analysis, parallel channels, RRF fusion, synthesis.              |
| Model gateway        | Provider-agnostic chat + embedding access; local (Ollama) by default.   |
| Storage layer        | Pluggable backend behind one interface: embedded (SQLite) or Postgres.  |
| Vectorization worker | Async embedding generation via transactional outbox.                    |

---

## 4. Technology stack

| Concern              | Choice                                       | Notes                                                  |
|----------------------|-----------------------------------------------|--------------------------------------------------------|
| Language / runtime   | Java 25                                       | Virtual threads + structured concurrency for fan-out.  |
| Framework            | Spring Boot 4.0.6                              | REST, DI, transactions; AOT for native image.          |
| LLM orchestration    | Spring AI 2.0.0-M6                             | Provider-agnostic chat + embedding + structured output.|
| Storage (default)    | **SQLite + `sqlite-vec` + FTS5**              | Embedded, single-file, zero-install.                   |
| Storage (server)     | PostgreSQL 16+ with `pgvector` 0.8+           | Opt-in multi-user backend.                             |
| Vector index         | `sqlite-vec` (embedded) / HNSW (`pgvector`)   | Same logical search, per backend.                      |
| Full-text search     | FTS5 `porter` (embedded) / `tsvector` (server)| Porter stemming on both.                               |
| Models (default)     | **Ollama (local)**                            | Hosted APIs (Anthropic/OpenAI) opt-in.                 |
| Async work           | Transactional outbox + virtual-thread workers | No broker dependency.                                  |
| SQLite driver        | `sqlite-jdbc` (xerial)                        | Loads `sqlite-vec` via `enableLoadExtension`.          |
| Migrations           | Flyway                                        | Per-backend migration sets.                            |
| Packaging            | GraalVM native image (preferred) / `jpackage` | Install bundle with daemon service + shim binary.      |
| Build                | Gradle (Kotlin DSL, 3 modules)                | `:daemon`, `:shim`, `:shared`; Boot/AOT per app module. |
| Testing              | JUnit 5; embedded SQLite + Testcontainers (PG)| Test both backends.                                    |

### 4.1 Models

Models are accessed behind a **model gateway** interface, swappable via configuration.
Two tiers (per the "bigger isn't always better" finding):

- **Small/fast model** — extraction, verification, classification, query analysis.
- **Large model** — final synthesis only (the one stage where more capacity helps).

**Default to Ollama** for both embedding and chat so a fresh install needs no network and
no API key: a local embedding model, a small local model for the structured stages, and a
local model for synthesis. Hosted APIs (Anthropic/OpenAI) and `vLLM` are opt-in for users
who want higher quality or throughput — a config change, since Spring AI supports all
three. First-run setup pulls the default Ollama models if absent.

---

## 5. Data model & storage backends

Memories are document-shaped but retrieval is multi-modal, so each backend keeps **typed
columns for everything we index or query** plus a **JSON payload for the heterogeneous,
per-type fields**. The logical model is identical across backends; only the physical
types and the vector/FTS mechanisms differ.

### 5.1 Logical model

| Entity   | Key fields                                                                          |
|----------|-------------------------------------------------------------------------------------|
| profile  | `id`, `name` (unique), `created_at`                                                 |
| message  | `id` (content-addressed), `profile_id`, `session_id`, `role`, `content`, `created_at` |
| memory   | `id` (content-addressed), `profile_id`, `session_id`, `type`, `content`, `topic_key`, `supersedes`, `superseded`, `payload` (JSON), `embedding`, `embed_text`, `created_at` |
| outbox   | `memory_id`, `enqueued_at`, `attempts`                                              |

### 5.2 Embedded backend (SQLite — default)

```sql
CREATE TABLE profiles (
    id          TEXT PRIMARY KEY,             -- uuid
    name        TEXT NOT NULL UNIQUE,
    created_at  TEXT NOT NULL                 -- ISO-8601
);

CREATE TABLE messages (
    id          TEXT PRIMARY KEY,             -- hex of SHA-256(session+role+content)[:16]
    profile_id  TEXT NOT NULL REFERENCES profiles(id),
    session_id  TEXT NOT NULL,
    role        TEXT NOT NULL,
    content     TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

CREATE TABLE memories (
    id          TEXT PRIMARY KEY,             -- content-addressed
    profile_id  TEXT NOT NULL REFERENCES profiles(id),
    session_id  TEXT,
    type        TEXT NOT NULL,                -- 'fact'|'event'|'instruction'|'task'
    content     TEXT NOT NULL,                -- canonical declarative statement
    topic_key   TEXT,                         -- normalized key (facts/instructions)
    supersedes  TEXT REFERENCES memories(id), -- forward pointer in version chain
    superseded  INTEGER NOT NULL DEFAULT 0,   -- 0/1
    payload     TEXT NOT NULL DEFAULT '{}',   -- JSON (json1/jsonb funcs)
    embed_text  TEXT,                         -- declarative + interrogative queries
    created_at  TEXT NOT NULL
);

-- Full-text search (Porter stemmer), external-content over memories/messages
CREATE VIRTUAL TABLE memories_fts USING fts5(
    content, content='memories', content_rowid='rowid', tokenize='porter');
CREATE VIRTUAL TABLE messages_fts USING fts5(
    content, content='messages', content_rowid='rowid', tokenize='porter');

-- Vector index (sqlite-vec). Tasks are NOT inserted here (kept lean).
CREATE VIRTUAL TABLE memories_vec USING vec0(
    memory_id TEXT PRIMARY KEY, embedding FLOAT[1024]);

-- Keyed lookup / type filter
CREATE INDEX idx_mem_profile_key  ON memories(profile_id, topic_key) WHERE superseded = 0;
CREATE INDEX idx_mem_profile_type ON memories(profile_id, type)      WHERE superseded = 0;

CREATE TABLE vectorization_outbox (
    memory_id   TEXT PRIMARY KEY REFERENCES memories(id),
    enqueued_at TEXT NOT NULL,
    attempts    INTEGER NOT NULL DEFAULT 0
);
```

SQLite notes: enable **WAL mode** for read concurrency under the single writer; JSON via
the `json1`/`jsonb` functions (`json_extract` for payload queries); FTS triggers keep the
external-content FTS tables in sync on insert/update/delete; `sqlite-vec` loaded at
startup via `enableLoadExtension`.

### 5.3 Server backend (Postgres)

Same logical model with native types: `BYTEA` ids, `JSONB` payload (+ GIN index),
`VECTOR(1024)` with an **HNSW** index (`vector_cosine_ops`), `TSVECTOR` generated columns
with **GIN** indexes for FTS, `TIMESTAMPTZ` timestamps, and partial indexes filtered on
`superseded = FALSE`. Supersession deletes the old vector in the same transaction as the
new upsert.

### 5.4 Storage abstraction

All persistence sits behind a single `MemoryStore` interface (plus Spring AI's
`VectorStore` seam for the vector ops). The five retrieval channels and the ingestion
writes are defined against this interface; `embedded` and `postgres` are two
implementations selected by configuration. This is what makes the local→server move a
backend swap rather than a rewrite.

### 5.5 Memory types and payloads

| Type          | Semantics                              | Keyed? | In vector index? | Example payload fields      |
|---------------|----------------------------------------|--------|------------------|-----------------------------|
| `fact`        | True now; atomic, stable knowledge.    | Yes    | Yes              | `{}`                        |
| `event`       | Happened at a specific time.           | No     | Yes              | `{ "occurred_at": "..." }`  |
| `instruction` | How to do something; procedure.        | Yes    | Yes              | `{ "steps": [...] }`        |
| `task`        | Currently being worked on; ephemeral.  | No     | **No**           | `{ "status": "open" }`      |

Tasks are excluded from the vector index to keep it lean but remain discoverable via FTS.

### 5.6 Supersession

Facts and instructions get a normalized `topic_key`. When a new memory shares a key with
an existing one, the old memory is **superseded, not deleted**: mark it `superseded` and
point the new row's `supersedes` at it, forming a version chain. The old memory's vector
is removed in the **same transaction** as the new upsert to avoid drift. Active queries
filter on the non-superseded set.

---

## 6. Ingestion pipeline (write path)

Input: a list of messages + `sessionId`. Output: stored, deduplicated memories.
Returns to the caller **before** vectorization completes (async).

1. **Content-addressed ID generation.** Each message ID = `SHA-256(sessionId + role +
   content)` truncated to 128 bits. Re-ingesting the same conversation resolves to
   identical IDs, making ingestion **idempotent** (insert-or-ignore on conflict).

2. **Extraction (two parallel passes).**
   - *Full pass:* chunk messages at ~10K characters with a two-message overlap; process
     up to four chunks concurrently. Each chunk gets a structured transcript with role
     labels, relative dates resolved to absolutes (`"yesterday"` → `"2026-04-14"`), and
     line indices for provenance.
   - *Detail pass* (conversations of 9+ messages): overlapping windows focused on concrete
     values — names, prices, version numbers, entity attributes — that broad extraction
     tends to miss.
   - Merge the two result sets.

3. **Verification.** Each extracted memory is checked against the source transcript across
   entity identity, object identity, location context, temporal accuracy, organizational
   context, completeness, relational context, and whether inferred facts are actually
   supported. Each item is **passed, corrected, or dropped**.

4. **Classification.** Each verified memory is classified into one of the four types.
   Facts and instructions receive a normalized `topic_key`. During this step, generate
   3–5 **interrogative search queries** for the memory (see §8.1).

5. **Supersession.** For keyed memories, look up existing memories with the same
   `(profile_id, topic_key)`; if found, supersede (see §5.6).

6. **Store.** Insert-or-ignore so content-addressed duplicates are skipped. Enqueue a row
   in the vectorization outbox.

7. **Async vectorization.** A virtual-thread worker drains the outbox: embed `embed_text`
   (declarative content prefixed with the interrogative queries — see §8.1), upsert the
   vector, delete superseded vectors.

---

## 7. Retrieval pipeline (read path)

Input: a recall query (+ profile). Output: a synthesized natural-language answer.
No single retrieval method wins for all queries, so run several in parallel and fuse.

### 7.1 Stages

1. **Query analysis + embedding (parallel).** The analyzer produces ranked topic keys,
   FTS terms with synonyms, and a **HyDE** statement (a hypothetical declarative answer).
   Concurrently, embed the raw query. Both embeddings are used downstream.

2. **Five retrieval channels (parallel).**
   - **Full-text search** — Porter-stemmed keyword precision over the memory FTS index.
   - **Exact fact-key lookup** — query maps directly to a known `topic_key`.
   - **Raw message search** — FTS over stored messages as a safety net for verbatim
     details the extractor may have generalized away.
   - **Direct vector search** — semantic similarity using the embedded raw query.
   - **HyDE vector search** — similarity to what the *answer* would look like; surfaces
     results direct embedding misses, especially abstract/multi-hop queries.

3. **Fusion (Reciprocal Rank Fusion).** Each result is scored by its rank within each
   channel, weighted by signal strength: fact-key highest, then FTS / HyDE / direct
   vectors, then raw-message matches lowest (safety net). Ties broken by recency.

4. **Synthesis.** Top candidates go to the large model, which writes a natural-language
   answer to the original query.

### 7.2 Deterministic special-casing

Temporal computation (date math, durations) is handled **deterministically** in Java
(regex + arithmetic) and injected into the synthesis prompt as pre-computed facts.
Models are unreliable at arithmetic, so we never ask them to do it.

### 7.3 RRF reference

```
score(doc) = Σ_channels  weight[channel] / (k + rank_in_channel(doc))     // k ≈ 60
```

---

## 8. Key design decisions

### 8.1 Embedding text: bridge declarative storage and interrogative queries

Memories are written declaratively (`"user prefers pnpm"`) but searched interrogatively
(`"what package manager does the user use?"`). At classification time, generate 3–5
interrogative queries and **prepend them to the memory content** to form `embed_text`,
which is what gets embedded. Decide this early — retrofitting means re-embedding the store.

### 8.2 Embedded-first, single store for multi-modal retrieval

The default backend is an embedded SQLite file that does vector search (`sqlite-vec`),
full-text search (FTS5 with Porter stemming), and keyed lookup in **one store, one
snapshot**. This keeps supersession atomic and avoids any cross-system coordination — and
it needs zero install. The same "one consistent store" property holds for the Postgres
server backend, so the retrieval design is backend-independent.

### 8.3 Document-shaped storage

Per-type heterogeneous fields live in a JSON `payload`; only fields we query or index are
promoted to typed columns. Schemaless iteration during early phases, with no migration
churn as the memory shape evolves.

### 8.4 Single daemon, thin shims

One local daemon is the single writer of the embedded store; harnesses connect through
thin MCP stdio shims. This is required by SQLite's single-writer model and is also what
gives shared memory across every tool on the machine.

### 8.5 Local models by default

Defaulting the model gateway to Ollama keeps a fresh install fully offline and free per
query, which is the point of a local-first tool. Hosted models stay one config flag away.

### 8.6 Two-tier model strategy

Small model for structured tasks (extraction/verification/classification/analysis), large
model only for synthesis. Both behind the model gateway.

---

## 9. API design

REST endpoints served by the daemon on localhost (local mode) or the shared host (server
mode). Resource-scoped by profile; all bodies JSON.

| Method | Path                                          | Purpose                                  |
|--------|-----------------------------------------------|------------------------------------------|
| POST   | `/v1/profiles/{name}/ingest`                  | Bulk-extract memories from a conversation.|
| POST   | `/v1/profiles/{name}/memories`                | Remember a single memory explicitly.     |
| POST   | `/v1/profiles/{name}/recall`                  | Run retrieval, return synthesized answer. |
| GET    | `/v1/profiles/{name}/memories`                | List memories (filter by type/session).  |
| DELETE | `/v1/profiles/{name}/memories/{id}`           | Forget a memory.                         |
| GET    | `/v1/profiles/{name}/export`                  | Export all memories (portability).       |

### 9.1 Example: recall

```http
POST /v1/profiles/my-project/recall
{ "query": "What package manager does the user prefer?" }

200 OK
{ "answer": "The user prefers pnpm over npm.", "memories": [ ... ] }
```

---

## 10. Harness integration (MCP shims + hooks)

Harnesses integrate through two surfaces that mirror the ingestion/retrieval split:

1. **Model-driven tools** — the model calls `recall`, `remember`, `list`, `forget`
   mid-task. Delivered as an **MCP stdio shim** that forwards to the daemon. Built as a
   dedicated shim artifact and reused by every harness, since all of them speak MCP.
2. **Harness-driven ingestion** — at compaction the harness ships the conversation to the
   daemon's `/ingest`. Delivered as a per-harness **lifecycle hook** (thin glue script).

`ingest` is intentionally **not** a model-facing tool — bulk ingestion is the harness's
job. Keeping the model's tool surface narrow is deliberate: the primary agent must not
burn context designing storage queries.

### 10.1 MCP shim

- **Implementation:** Spring AI MCP (built on the official MCP Java SDK). The shim is a
  thin stdio client packaged separately from the daemon; the daemon holds all state. (In
  server mode the shim may instead use streamable HTTP directly to the shared host.)
- **Transport:** stdio (local harness ↔ shim), then localhost HTTP (shim ↔ daemon).
- **Tools exposed (and their daemon mappings):**

| MCP tool   | Daemon mapping                                | Model-facing? |
|------------|-----------------------------------------------|---------------|
| `recall`   | `POST /v1/profiles/{name}/recall`             | Yes           |
| `remember` | `POST /v1/profiles/{name}/memories`           | Yes           |
| `list`     | `GET  /v1/profiles/{name}/memories`           | Yes           |
| `forget`   | `DELETE /v1/profiles/{name}/memories/{id}`    | Yes           |
| `ingest`   | `POST /v1/profiles/{name}/ingest`             | **No** (hook) |

Harnesses surface these as `mcp__pieria__recall`, etc.

### 10.2 Profile mapping

Default convention is **profile-per-repo**: derive the name from the git remote or project
directory, supply it to the shim via env var or config, and use the harness's own session
ID as `sessionId`. Because all harnesses share the daemon, pointing them at the same
profile name gives shared memory across tools (local) or across people (server).

### 10.3 Recall timing

- **On demand** — the model calls `recall` mid-task via MCP (precise, model-initiated).
- **At session start / post-compaction** — the hook injects relevant memories so context
  is primed even if the model doesn't ask (guaranteed, harness-initiated).

### 10.4 Per-harness wiring

| Harness     | Tools (MCP)                          | Ingestion hook                                  | Recall-at-start                       |
|-------------|--------------------------------------|-------------------------------------------------|---------------------------------------|
| Claude Code | `claude mcp add` (settings.json)     | `PreCompact` hook + periodic `Stop` hook        | `SessionStart` hook                   |
| OpenCode    | `mcp` key in `opencode.json`         | `experimental.session.compacting` plugin hook   | `experimental.chat.system.transform`¹ |
| Codex CLI   | `[mcp_servers]` in `config.toml`     | `Stop` hook (no compaction-specific event)²     | `SessionStart` hook                   |
| Custom      | MCP client or direct REST            | call `/ingest` at the harness compaction step   | call `/recall` on session bootstrap   |

¹ OpenCode has no `SessionStart` hook yet (issue #14808); `experimental.chat.system.transform`
is the community surrogate for injecting prior-session memories.
² Codex command hooks are recent and command-only (prompt/agent handlers are skipped);
verify the current event list against Codex docs, as this area is evolving.

### 10.5 Distribution

For Claude Code, bundle the shim registration + `PreCompact`/`Stop`/`SessionStart` hooks
into a single installable plugin via a marketplace manifest — one `claude plugin add`.
OpenCode ships as an npm plugin referenced in `opencode.json`. Codex is configured via
`config.toml`. The installer also registers and starts the daemon as an OS service (§14).

---

## 11. Concurrency model

Both pipelines fan out into parallel LLM/DB calls. Use Java 25 virtual threads with
structured concurrency so a stage's parallel sub-tasks share a lifecycle and fail/cancel
together.

```java
// Retrieval fan-out: five channels in parallel, joined, then fused.
// Java 25 structured-concurrency API: open() waits for all subtasks and
// propagates the first failure (cancelling the rest).
try (var scope = StructuredTaskScope.open()) {
    var fts        = scope.fork(() -> ftsChannel.search(query));
    var keys       = scope.fork(() -> keyChannel.lookup(analysis.topicKeys()));
    var rawMsgs    = scope.fork(() -> messageChannel.search(query));
    var directVec  = scope.fork(() -> vectorChannel.search(queryEmbedding));
    var hydeVec    = scope.fork(() -> vectorChannel.search(hydeEmbedding));

    scope.join();   // waits for all; throws if any subtask failed

    return rrf.fuse(List.of(
        fts.get(), keys.get(), rawMsgs.get(), directVec.get(), hydeVec.get()));
}
```

The daemon is the **single writer** of the embedded store: writes are serialized through
it (SQLite WAL allows concurrent reads). Configure model clients with virtual-thread
executors so blocking calls scale without a bounded pool.

---

## 12. Tenancy & isolation

- **Local mode (default):** single user; no Row-Level Security. Profiles are used purely
  for organization (per project) and are all owned by the local user. The daemon binds
  localhost only.
- **Server mode:** every row carries `profile_id`; enforce isolation with Postgres
  **Row-Level Security** keyed on a session-scoped `profile_id`, or schema-per-tenant for
  stricter isolation. Add authentication at the API boundary.
- Profiles are addressable by name (`getProfile("my-project")`), resolved to `id` at the
  API boundary in both modes.

---

## 13. Data portability

Every memory is exportable via `GET /export` (newline-delimited JSON of memories and
provenance). This is both a feature ("your memories are yours") and the mechanism for the
local→server migration: export from the embedded store, import into Postgres, re-embed.

---

## 14. Packaging & operation

- **Distributable:** installable bundle containing two app artifacts: the daemon
  (`:daemon`, long-running service, owns storage) and the MCP stdio shim (`:shim`,
  harness-spawned client). GraalVM native images are preferred — especially for the shim's
  cold start and the daemon's memory footprint — with JVM boot jars / `jpackage` as the
  fallback. Native image requires Spring AOT and reflection/resource config, plus
  per-platform bundling of the `sqlite-vec` native extension for the daemon; budget
  build-system time for this.
- **Service registration:** installer registers the daemon with launchd / systemd /
  Windows service and starts it on login/boot. The shim is not a service; harnesses launch
  it on demand via the configured MCP stdio command.
- **Data location:** embedded DB and config under an OS-appropriate app data dir (e.g.
  `~/.local/share/pieria/` on Linux); single file, easy to back up or delete.
- **First-run:** pulls default Ollama models if absent; creates the embedded DB and runs
  migrations; prints the localhost port and the per-harness setup snippets.
- **Health/observability:** `/healthz`; per-stage token and latency metrics (local logs
  by default, no telemetry leaves the machine).

---

## 15. Evaluation harness

Build this early; it gates quality work. Treat every prompt or fusion-weight change as
measured, not vibes.

- **Benchmarks:** LoCoMo, LongMemEval (and optionally BEAM) for apples-to-apples recall.
- **Stochasticity:** LLMs vary even at temperature 0 — average multiple runs and rely on
  trend analysis alongside raw scores.
- **Overfitting guard:** prefer changes that generalize; review proposals that only move a
  single benchmark.
- **Local-model tracking:** measure the quality gap between default local models and a
  hosted baseline so the cost of staying offline is explicit.

---

## 16. Phased build plan

| Phase | Deliverable                                                                                       |
|-------|---------------------------------------------------------------------------------------------------|
| 1     | Walking skeleton on **embedded SQLite**: schema, naive `ingest` (1 LLM call via Ollama), `recall` (vector + synthesis). |
| 2     | Full ingestion: content IDs, parallel extraction, verification, classification, supersession.     |
| 3     | Full retrieval: five channels (sqlite-vec + FTS5 + keyed) + RRF + HyDE; deterministic temporal.   |
| 4     | Daemon + thin MCP shim + Claude Code plugin (PreCompact/Stop/SessionStart); shared across tools.  |
| 5     | Packaging: daemon + shim native/JVM bundle, OS service install, first-run model pull, eval harness. |
| 6     | Server mode: Postgres backend behind the storage seam, RLS multi-tenancy, export/import migration. |

---

## 17. Future considerations

- **Server / team mode** (Phase 6): shared host, Postgres backend, multi-user isolation —
  same code, swapped backend. The export endpoint is the migration path.
- **Dedicated vector DB (Qdrant):** only relevant at server scale — a single profile in
  the millions of vectors, sustained QPS/latency beyond pgvector, or RAM cost warranting
  quantization. Fed as a derived index from the vectorization outbox; vector ops already
  sit behind the `VectorStore` seam.
- **Async memory consolidation:** background "replay" to strengthen/merge memories over
  time (analogous to sleep consolidation).
- **Encryption at rest** for the embedded DB (e.g. SQLCipher) as an opt-in.

---

## 18. Open questions

- Default local embedding + chat models (quality vs. size vs. RAM) and embedding
  dimensionality (fixes the `FLOAT[n]` / `VECTOR(n)` width and re-embedding cost).
- `sqlite-vec` maturity and recall at target scale vs. DuckDB VSS as an alternative
  embedded backend.
- Chunk size / overlap tuning per local model context window.
- RRF weights and `k` — start from defaults, tune against the eval harness.
- Retention/TTL policy for ephemeral `task` memories.
- Native-image build effort for the `sqlite-vec` extension across macOS/Linux/Windows.
