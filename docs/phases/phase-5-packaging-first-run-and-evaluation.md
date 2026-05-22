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
   - Ensure the normal boot jar can run the daemon and shim entry points.
   - Include required resources, Flyway migrations, configuration metadata, and harness assets.
   - Add smoke commands for starting the daemon with a temporary data directory.

2. Configure GraalVM native image packaging.
   - Use Spring AOT and the existing GraalVM Gradle plugin.
   - Add reflection/resource configuration needed by Spring AI, SQLite, Flyway, validation, JSON, and MCP code.
   - Package the embedded SQLite/vector native libraries per platform.
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
   - Print daemon URL, profile mapping behavior, and harness setup snippets.
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

- Packaging tests for boot jar resource inclusion and startup with a temporary data directory.
- Native image build smoke test on supported environments.
- First-run tests for idempotent directory creation, migration, model check handling, and setup output.
- Service script tests for generated launchd/systemd content.
- Evaluation harness tests using deterministic fixtures and fake model responses.
- Run `./gradlew test`; run `./gradlew nativeCompile` on environments configured for GraalVM 25+.

## Acceptance Criteria

- Pieria can run from a packaged JVM artifact.
- Native image packaging is configured and documented, with a working build on supported local environments.
- First-run setup initializes directories, database, migrations, and model checks idempotently.
- launchd service assets are available first, with systemd and Windows coverage added or clearly staged.
- Local logs expose useful health, latency, token, and outbox metrics without remote telemetry.
- Evaluation reports can be generated from local fixtures.

## Risks And Follow-Ups

- Native image support for Spring AI, MCP, SQLite, and vector native libraries may require iterative reflection/resource configuration.
- OS service behavior must be tested per platform; script generation tests do not replace real install checks.
- Benchmark adapters may require dataset licensing or manual download steps, so keep local fixtures as the always-available quality gate.
