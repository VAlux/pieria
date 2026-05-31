# Pieria

**A local-first, persistent memory layer for AI agents.**

[![release](https://github.com/VAlux/pieria/actions/workflows/release.yml/badge.svg)](https://github.com/VAlux/pieria/actions/workflows/release.yml)

> *Pieria* — the region at the foot of Mount Olympus that was the mythic home of the
> Muses and the site of the Pierian spring, the classical metaphor for the source of
> knowledge and memory.

Pieria gives your AI coding tools a shared, long-lived memory. You install it once, it
runs as a background daemon on `localhost`, and every MCP-capable agent on your machine —
Claude Code, OpenCode, Codex, and others — reads and writes the same memory store. What
one tool learns, the next one already knows.

It runs entirely on your own machine. No cloud account, no API key, and no network
round-trip are required for a recall: the default setup pairs an embedded database with a
local model runtime (Ollama). Hosted models (Anthropic, OpenAI) and a multi-user server
mode are opt-in, the same code with a different backend.

---

## Why Pieria

AI agents forget everything between sessions, and their context windows fill up. Pieria is
the durable memory that survives compaction and restarts:

- It **extracts** durable knowledge from agent conversations — facts, events, instructions,
  and tasks — instead of forcing the agent to keep everything in context.
- It **deduplicates and supersedes** memories so the store stays accurate as it grows,
  rather than accumulating stale or contradictory entries.
- It **retrieves** the relevant subset on demand through fused multi-modal search, and
  returns a synthesized natural-language answer.

## Features

- **Local-first & private** — runs offline by default; nothing leaves your machine.
- **Shared across tools** — one memory store serves every MCP harness simultaneously.
- **Structured memory** — every memory is classified as a `fact`, `event`, `instruction`,
  or `task`, with per-type fields.
- **Smart ingestion** — content-addressed IDs make ingest idempotent; parallel extraction,
  verification, classification, and supersession keep the store clean.
- **Multi-modal retrieval** — five parallel channels (full-text search, exact key lookup,
  raw-message search, direct vector, and HyDE vector) fused with Reciprocal Rank Fusion.
- **Deterministic temporal reasoning** — date math is computed in code, not guessed by a
  model.
- **Vendor-neutral & exportable** — your memories export to NDJSON; swap model providers or
  scale up to a shared Postgres server without a rewrite.

## How it works

```
  Agent harnesses ──► MCP stdio gateways ──► Local daemon ──► Embedded store (SQLite + vec + FTS5)
  (any MCP harness)    (thin clients)       (binds 127.0.0.1)  └─► Local models (Ollama, default)
```

A single background **daemon** owns the embedded database and pipelines and binds an HTTP
API to `127.0.0.1`. Each harness launches a tiny **MCP stdio gateway** that forwards tool
calls to the daemon. Because the embedded database is single-writer, funnelling every
harness through one daemon both avoids write-lock contention and delivers the shared-memory
goal.

The daemon runs two pipelines over a pluggable storage backend:

- **Write path (ingest):** conversation → parallel extraction → verification → classification
  → supersession → store → async vectorization.
- **Read path (recall):** query analysis + embedding → five parallel retrieval channels →
  RRF fusion → synthesis.

See [`docs/SPEC.md`](docs/SPEC.md) for the full specification and
[`docs/PLAN.md`](docs/PLAN.md) for the phased implementation plan.

## Stack

- **Java 25**, **Spring Boot 4.0.6**, Gradle (Kotlin DSL)
- **Spring AI** for provider-agnostic chat + embeddings (Ollama default; Anthropic/OpenAI opt-in)
- **SQLite + sqlite-vec + FTS5** embedded backend (default); **PostgreSQL + pgvector** for server mode
- **Flyway** migrations, **JUnit 5** tests, **GraalVM native-image** packaging (JVM fallback)

The repository is split into Gradle modules under `modules/`:

| Module    | Responsibility                                                        |
|-----------|-----------------------------------------------------------------------|
| `shared`  | HTTP request/response DTOs and `ProfileResolver`.                     |
| `daemon`  | REST controllers, domain, storage, ingestion, retrieval, model gateway.|
| `gateway` | stdio MCP tools and the HTTP client that forwards to the daemon.      |
| `cli`     | the `pieria` command — harness wiring and profile management.         |
| `eval`    | offline evaluation harness — fixtures, runner, benchmark adapters.    |

---

## Installation

### Quick install (macOS / Linux)

The installer downloads the native daemon + gateway binaries for your platform, links them
onto your `PATH`, and registers the daemon as a per-user OS service (launchd on macOS,
systemd on Linux). Re-running is safe — every step is idempotent.

```bash
curl -fsSL https://raw.githubusercontent.com/VAlux/pieria/main/packaging/install.sh | bash
```

> Inspect any script before piping it to a shell. You can also download `install.sh` and run
> `bash install.sh --dry-run` to preview every step and resolved URL without changing anything.

Useful flags:

```bash
bash install.sh --no-service     # install binaries only, skip OS service registration
bash install.sh --version vX.Y.Z # pin a specific release tag
bash install.sh --help           # full option list
```

**Windows:** use the PowerShell installer instead:

```powershell
packaging/install.ps1
```

Once installed, the daemon listens on `http://127.0.0.1:8077`. On first start it
initializes the embedded database, runs migrations, and prints guidance for pulling the
default Ollama models if they are absent.

### Wire a harness

From inside a project, register the MCP gateway and lifecycle hooks for your tool:

```bash
pieria harness install claude-code        # or: codex
pieria harness install claude-code --user # wire ~/.claude instead of this repo
```

Preview, inspect, or undo:

```bash
pieria harness install claude-code --dry-run   # preview the changes first
pieria harness list                            # see what's wired
pieria harness uninstall claude-code           # undo
```

The default convention is **profile-per-repo**: the profile name is derived from the git
remote or project directory, so pointing every harness at the same profile gives shared
memory across tools.

### Seed the profile from project docs

A fresh profile starts empty, so `recall` returns nothing until a few sessions have
accumulated. `pieria init` solves this cold start by seeding the profile from the project's
existing markdown documentation:

```bash
pieria init             # seed from every .md in the repo (except CLAUDE.md / AGENTS.md)
pieria init --dry-run   # list the docs and messages that would be sent, contact nothing
```

It enumerates docs via `git ls-files` (so build output and gitignored files are skipped),
packages them as a transcript, and runs them through the normal ingest pipeline — the
daemon's extraction, verification, and supersession keep low-signal content out.
`CLAUDE.md` and `AGENTS.md` are excluded by default since harnesses already load them into
context every session; pass `--include-agent-docs` to seed them too. Re-running is
idempotent: unchanged docs add no duplicate memories.

> Requires the daemon to be running (and a model provider reachable). Useful flags:
> `--profile <slug>`, `--daemon-url <url>`.

### Build from source

Requires JDK 25 (and GraalVM 25+ for native images).

```bash
./gradlew build                 # compile + test + assemble
./gradlew test                  # run the full test suite
./gradlew :daemon:bootRun       # run the daemon locally
./gradlew :daemon:bootJar       # → modules/daemon/build/libs/pieria.jar
./gradlew :gateway:bootJar      # → modules/gateway/build/libs/pieria-gateway.jar
./gradlew :daemon:nativeCompile # GraalVM daemon executable
./gradlew :gateway:nativeCompile
```

---

## Usage

Harnesses interact through MCP tools that mirror the ingestion/retrieval split:

| MCP tool   | Purpose                                          | Model-facing? |
|------------|--------------------------------------------------|---------------|
| `recall`   | Run retrieval, return a synthesized answer.      | Yes           |
| `remember` | Store a single memory explicitly.                | Yes           |
| `list`     | List memories (filter by type/session).          | Yes           |
| `forget`   | Mark a memory as no longer valid.                | Yes           |
| `ingest`   | Bulk-extract memories from a conversation.       | No (hook)     |

`ingest` is driven by harness lifecycle hooks (e.g. at compaction), not by the model — the
primary agent shouldn't burn context designing storage queries.

The daemon also exposes a REST API on `localhost`, scoped by profile:

| Method | Path                                  | Purpose                                   |
|--------|---------------------------------------|-------------------------------------------|
| POST   | `/v1/profiles/{name}/ingest`          | Bulk-extract memories from a conversation.|
| POST   | `/v1/profiles/{name}/memories`        | Store a single memory explicitly.         |
| POST   | `/v1/profiles/{name}/recall`          | Run retrieval, return a synthesized answer.|
| GET    | `/v1/profiles/{name}/memories`        | List memories (filter by type/session).   |
| DELETE | `/v1/profiles/{name}/memories/{id}`   | Forget a memory.                          |
| GET    | `/v1/profiles/{name}/export`          | Export all memories (NDJSON).             |

Example recall:

```http
POST /v1/profiles/my-project/recall
{ "query": "What package manager does the user prefer?" }

200 OK
{ "answer": "The user prefers pnpm over npm.", "memories": [ ... ] }
```

---

## Configuration

The daemon binds to `127.0.0.1` by default and never exposes a public interface in local
mode. The embedded database and config live under an OS-appropriate data directory
(`~/.local/share/pieria/` by default). Key settings: database path, daemon host/port,
Ollama base URL, chat and embedding model names, and embedding dimension.

Model access sits behind a swappable gateway with two tiers — a small/fast model for
structured stages (extract/verify/classify) and a larger model for synthesis only. Hosted
providers (Anthropic, OpenAI) are a config change away.

## Data portability

Every memory is exportable via `GET /export` as newline-delimited JSON. Your memories are
yours — and the same export is the migration path to server mode: export from SQLite,
import into Postgres, re-embed.