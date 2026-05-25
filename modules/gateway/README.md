# gateway

The `gateway` module is the MCP stdio server that harnesses (Claude Code, OpenCode, Codex, etc.) spawn as a subprocess to access Pieria's memory tools. It is a **stateless, thin forwarder**: it holds no database, no pipelines, and no model connection. Every tool call is translated into an HTTP request to the daemon's REST API and the raw JSON response is returned as-is to the model.

## Why it is a separate process

MCP harnesses communicate with tool servers over **stdin/stdout** — one subprocess per harness session. The daemon, by contrast, must be a single persistent process that is the sole writer of the embedded SQLite store (SQLite is single-writer; concurrent direct access would deadlock). The gateway is the stdio↔HTTP bridge that satisfies both constraints simultaneously:

- Each harness spawns its own gateway instance (stdio, per-session).
- All gateway instances forward to the same daemon (HTTP, single writer).
- The gateway carries no state, so it is trivially cheap to spawn and throw away.

## Tools exposed

The gateway registers four model-facing MCP tools. `ingest` is intentionally absent — bulk ingestion is a harness hook responsibility, not a model tool.

| MCP tool | Daemon endpoint | Description |
|---|---|---|
| `recall` | `POST /v1/profiles/{name}/recall` | Run retrieval, return a synthesized answer |
| `remember` | `POST /v1/profiles/{name}/memories` | Store a single memory explicitly |
| `list` | `GET /v1/profiles/{name}/memories` | List stored memories (optional type/session filter) |
| `forget` | `DELETE /v1/profiles/{name}/memories/{id}` | Mark a memory as no longer valid |

Harnesses surface these as `mcp__pieria__recall`, `mcp__pieria__remember`, etc.

## Package structure

```
dev.alvo.pieria
├── config/
│   └── GatewayProperties.java   pieria.gateway.* config properties
├── gateway/
│   └── GatewayApplication.java  Spring Boot entry point (non-web, stdio MCP)
└── mcp/
    ├── DaemonClient.java         Typed HTTP client to the daemon REST API
    ├── DaemonUnavailableException.java
    ├── GatewayConfig.java        Bean wiring: DaemonClient + MemoryTools
    ├── GatewayNativeHints.java   GraalVM reflection hints for shared contract DTOs
    └── MemoryTools.java          @Tool-annotated methods registered with Spring AI MCP
```

## Profile resolution

The default profile is derived from the working directory at startup using `ProfileResolver` (from `shared`): `$PIERIA_PROFILE` → git remote slug → directory basename, normalized to a `[a-z0-9-]` slug. Every tool also accepts an optional `profile` override parameter. Because the daemon resolves profiles by name, pointing multiple harnesses at the same profile name gives shared memory across tools.

## Configuration

| Property | Default | Environment override |
|---|---|---|
| `pieria.gateway.daemon-url` | `http://127.0.0.1:8077` | `PIERIA_DAEMON_URL` |

Set `PIERIA_DAEMON_URL` in the MCP server's `env` block (see `harness/` for per-harness examples) to point the gateway at a non-default daemon address.

## Daemon-down behaviour

If the daemon is unreachable (connection refused or timeout), `DaemonUnavailableException` is caught inside each tool and a concise human-readable string is returned to the model instead of propagating a stack trace. This keeps the model's tool result meaningful rather than breaking its context.

## Building

```bash
# Build the executable jar
./gradlew :gateway:bootJar
# → modules/gateway/build/libs/pieria-gateway.jar

# Build a GraalVM native executable (requires GraalVM 25+)
# Optimised for cold-start: this binary is spawned fresh by each harness session.
./gradlew :gateway:nativeCompile
# → modules/gateway/build/native/nativeCompile/pieria-gateway
```

## Running

The gateway is always launched by a harness, not started manually. The MCP config snippet looks like:

```json
{
  "mcpServers": {
    "pieria": {
      "command": "java",
      "args": ["-jar", "/path/to/pieria-gateway.jar"],
      "env": {
        "PIERIA_DAEMON_URL": "http://127.0.0.1:8077"
      }
    }
  }
}
```

Or with the native binary:

```json
{
  "mcpServers": {
    "pieria": {
      "command": "/path/to/pieria-gateway",
      "env": {
        "PIERIA_DAEMON_URL": "http://127.0.0.1:8077"
      }
    }
  }
}
```

See `harness/` in the repo root for ready-made config files for Claude Code, OpenCode, and Codex.

## Testing

```bash
./gradlew :gateway:test
```

`MemoryToolsTests` drives `DaemonClient` and `MemoryTools` against a lightweight in-process HTTP server (no Spring context, no real daemon required). The complementary end-to-end coverage lives in `:daemon`'s `GatewayDaemonSmokeTests`, which boots the real daemon and points a real `DaemonClient` at it.
