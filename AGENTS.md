# Repository Guidelines

## Project Structure & Module Organization

This is a multi-module Gradle Kotlin DSL project named `pieria`. The `daemon` module contains the Spring Boot REST daemon, with `PieriaApplication` as the entry point. The `shim` module contains the MCP stdio shim, with `ShimApplication` as the entry point. The `shared` module contains the HTTP contract DTOs and profile mapping logic used across processes.

Daemon configuration belongs in `daemon/src/main/resources`; shim configuration belongs in `shim/src/main/resources` when needed. Tests live under each module's `src/test/java/dev/alvo/pieria`. The generated Testcontainers development launcher is in the daemon module. Keep future package paths aligned with `dev.alvo.pieria`. Project-level documentation and specs belong at the repository root, for example `docs/SPEC.md` and `docs/HARNESS.md`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper so builds use the expected Gradle version.

- `./gradlew test` runs the JUnit Platform test suite.
- `./gradlew build` compiles, tests, and assembles the project.
- `./gradlew :daemon:bootRun` starts the daemon locally.
- `./gradlew :daemon:bootJar` builds `daemon/build/libs/pieria.jar`.
- `./gradlew :shim:bootJar` builds `shim/build/libs/pieria-shim.jar`.
- `./gradlew :daemon:bootBuildImage` builds a daemon container image named from the project/version.
- `./gradlew :daemon:nativeCompile` or `./gradlew :shim:nativeCompile` builds a GraalVM native executable; it requires GraalVM 25+.

## Coding Style & Naming Conventions

Use Java 25 language/toolchain settings from `build.gradle.kts`. Follow standard Java formatting with tabs for indentation, matching the existing generated source. Use `PascalCase` for classes, `camelCase` for methods and fields, and lowercase package names. Prefer constructor injection for Spring components and keep application, persistence, web, and configuration classes in clearly named packages as the codebase grows.

## Testing Guidelines

The project uses JUnit 5 through Spring Boot test starters and `useJUnitPlatform()`. Name unit and integration test classes with a `*Tests` suffix, mirroring `PieriaApplicationTests`. Use `@SpringBootTest` only when a full application context is needed; otherwise prefer narrower slice or unit tests. Run `./gradlew test` before submitting changes.

## Commit & Pull Request Guidelines

The current history is minimal and uses a short imperative-style subject, for example `scaffolding`. Keep commits concise, present-tense, and focused on one change. Pull requests should include a short summary, linked issue or spec reference when available, test results such as `./gradlew test`, and screenshots only for user-visible web changes.

## Configuration & Security Notes

Do not commit local secrets, credentials, or machine-specific paths. Keep environment-specific configuration outside source control or in ignored local overrides. Document new required environment variables in repository documentation when adding them.
