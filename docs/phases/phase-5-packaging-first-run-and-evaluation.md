# Phase 5 - Packaging, First Run, And Evaluation

## Objective

Make Pieria installable and measurable. This phase adds native/JVM packaging, first-run setup, OS service scripts, local observability, and an evaluation harness that can guide retrieval and prompt changes.

## Scope

- Production-like local distribution.
- First-run initialization and local operations.
- Evaluation harness with deterministic fixtures first, then benchmark adapters.
- Server mode remains out of scope until Phase 6.

## Implementation Sequence

1. Configure JVM packaging fallback.
   - Build and document separate boot jars: `:daemon:bootJar` (`pieria.jar`) and
     `:gateway:bootJar` (`pieria-gateway.jar`).
   - Include daemon resources, Flyway migrations, and configuration metadata in the daemon
     artifact.
   - Include MCP gateway configuration and shared contract classes in the gateway artifact, while
     keeping JDBC/Flyway/SQLite/Ollama daemon dependencies off the gateway runtime classpath.
   - Package harness assets with placeholders that point to the gateway artifact.
   - Add smoke commands for starting the daemon with a temporary data directory and running
     the gateway against it.

2. Configure GraalVM native image packaging.
   - Use Spring AOT and the existing GraalVM Gradle plugin.
   - Produce separate native binaries for the daemon and gateway; optimize the gateway for fast
     harness-spawned cold start.
   - Add reflection/resource configuration needed by Spring AI, SQLite, Flyway, validation,
     JSON, and MCP code.
   - Package the embedded SQLite/vector native libraries per platform with the daemon
     distribution only.
   - Keep native image failures actionable with documented prerequisites.

3. Define app data locations.
   - Use OS-appropriate directories for database, config, logs, and runtime metadata.
   - Keep paths configurable for tests and advanced users.
   - Avoid committing machine-specific paths to generated config.
   - Document backup and reset behavior for the embedded database file.

4. Implement first-run initialization.
   - Create app data directories.
   - Initialize or migrate the database.
   - Check Ollama reachability when Ollama is the configured provider.
   - Check required chat and embedding models.
   - Offer or perform model pull behavior according to configuration.
   - Print daemon URL, profile mapping behavior, and harness setup snippets that reference
     the installed gateway command or `pieria-gateway` path.
   - Ensure first-run can be repeated safely.

5. Add local observability.
   - Log startup configuration without secrets.
   - Log per-stage ingestion and retrieval latency.
   - Log token usage where available.
   - Add local health/status output for database path, backend, provider, model names, and outbox depth.
   - Do not add remote telemetry.

6. Add OS service installation scripts.
   - Implement launchd first for macOS.
   - Add systemd service/user unit for Linux.
   - Add Windows service installation guidance or scripts after the native/JVM command shape is stable.
   - Include install, start, stop, status, and uninstall flows.
   - Register only the daemon as an OS service; install the gateway as an executable used by
     harness MCP configs.
   - Validate scripts with dry-run or generated-file tests where possible.

7. Build fixture-based evaluation first.
   - Create local fixtures with transcripts, expected memories, expected recall evidence, and expected answers.
   - Run evaluation without network access using fake or pinned model outputs where possible.
   - Track extraction precision/recall, retrieval hit rate, ranking quality, answer faithfulness, latency, and token usage.
   - Store reports in a local ignored output directory.

8. Add benchmark adapters.
   - Add adapters for LoCoMo and LongMemEval after fixture harness shape is stable.
   - Keep benchmark data acquisition outside normal tests unless fixtures are checked in or generated locally.
   - Average multiple runs for stochastic model calls.
   - Compare default local models against an optional hosted baseline when configured.

9. Use evaluation to tune defaults.
   - Tune RRF weights and limits using fixture and benchmark reports.
   - Track prompt changes with before/after metrics.
   - Avoid tuning only for one benchmark at the cost of local fixture regressions.

## Tests

- Packaging tests for daemon/gateway boot jar resource inclusion, dependency separation, and
  startup with a temporary data directory.
- Native image build smoke test on supported environments.
- First-run tests for idempotent directory creation, migration, model check handling, and setup output.
- Service script tests for generated launchd/systemd content.
- Gateway smoke tests for daemon-down messaging and daemon-backed MCP tool forwarding from the
  packaged command shape.
- Evaluation harness tests using deterministic fixtures and fake model responses.
- Run `./gradlew test`; run `./gradlew :daemon:nativeCompile` and
  `./gradlew :gateway:nativeCompile` on environments configured for GraalVM 25+.

## Acceptance Criteria

- Pieria can run from packaged JVM artifacts: daemon process plus harness-spawned gateway
  process.
- Native image packaging is configured and documented for both daemon and gateway, with a
  working build on supported local environments.
- First-run setup initializes directories, database, migrations, and model checks idempotently.
- launchd service assets are available first, with systemd and Windows coverage added or clearly staged.
- Local logs expose useful health, latency, token, and outbox metrics without remote telemetry.
- Evaluation reports can be generated from local fixtures.

## Risks And Follow-Ups

- Native image support for Spring AI, MCP, SQLite, and vector native libraries may require
  iterative reflection/resource configuration; daemon and gateway should keep separate
  reachability metadata where possible.
- OS service behavior must be tested per platform; script generation tests do not replace real install checks.
- Benchmark adapters may require dataset licensing or manual download steps, so keep local fixtures as the always-available quality gate.
