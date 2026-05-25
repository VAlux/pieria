# shared

The `shared` module is a plain Java library — no Spring Boot, no web server — that defines the HTTP contract between the `daemon` and `gateway` modules. Every class here is compiled into both the daemon (which serves the API) and the gateway (which consumes it), making it the single point of truth for request/response shapes and profile-name logic.

## Contents

### HTTP request records (`dev.alvo.pieria.api.request`)

| Class | Used by |
|---|---|
| `IngestRequest` | `POST /v1/profiles/{name}/ingest` |
| `RecallRequest` | `POST /v1/profiles/{name}/recall` |
| `RememberRequest` | `POST /v1/profiles/{name}/memories` |

### HTTP response records (`dev.alvo.pieria.api.response`)

| Class | Description |
|---|---|
| `MemoryResponse` | Single memory (id, type, content, topicKey, sessionId, createdAt) |
| `MemoryListResponse` | Wrapper for `GET /memories` |
| `RecallResponse` | Synthesized answer + supporting memories + optional debug block |
| `IngestResponse` | Count of stored memories returned from `/ingest` |
| `HealthResponse` | Body for `/healthz` |
| `StatusResponse` | Body for `/status` |
| `ErrorResponse` | Uniform error envelope |

### Profile resolution (`dev.alvo.pieria.mapping`)

`ProfileResolver` derives a normalized memory-profile name from the working directory using a deterministic three-step cascade: `$PIERIA_PROFILE` env var → git remote slug → directory basename. The result is lowercased and stripped to `[a-z0-9-]`. Both the gateway and the harness hook scripts use this class so they always agree on the profile name for the same working directory.

## Dependency rules

- Depends on **nothing else in this repo**. No daemon, no gateway, no eval.
- `daemon` and `gateway` both declare `implementation(project(":shared"))`.
- `eval` does not use `shared` directly; it reaches the HTTP contract only through the daemon's plain jar.

## Building

```bash
./gradlew :shared:build
```

No bootJar, no application entry point — the output is a plain `.jar` consumed as a library.
