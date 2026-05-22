# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Gradle Kotlin DSL project named `pieria`. Application code lives under `src/main/java/dev/alvo/pieria`, with `PieriaApplication` as the Spring Boot entry point. Configuration belongs in `src/main/resources`, currently `application.properties`.

Tests live under `src/test/java/dev/alvo/pieria`. The generated Testcontainers development launcher is `TestPieriaApplication`, and shared test configuration is in `TestcontainersConfiguration`. Keep future package paths aligned with `dev.alvo.pieria`. Project-level documentation and specs belong at the repository root, for example `SPEC.md` and `HELP.md`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper so builds use the expected Gradle version.

- `./gradlew test` runs the JUnit Platform test suite.
- `./gradlew build` compiles, tests, and assembles the project.
- `./gradlew bootRun` starts the Spring Boot application locally.
- `./gradlew bootBuildImage` builds a container image named from the project/version.
- `./gradlew nativeCompile` builds a GraalVM native executable; it requires GraalVM 25+.
- `./gradlew nativeTest` runs tests in a native image when native tooling is configured.

## Coding Style & Naming Conventions

Use Java 25 language/toolchain settings from `build.gradle.kts`. Follow standard Java formatting with tabs for indentation, matching the existing generated source. Use `PascalCase` for classes, `camelCase` for methods and fields, and lowercase package names. Prefer constructor injection for Spring components and keep application, persistence, web, and configuration classes in clearly named packages as the codebase grows.

## Testing Guidelines

The project uses JUnit 5 through Spring Boot test starters and `useJUnitPlatform()`. Name unit and integration test classes with a `*Tests` suffix, mirroring `PieriaApplicationTests`. Use `@SpringBootTest` only when a full application context is needed; otherwise prefer narrower slice or unit tests. Run `./gradlew test` before submitting changes.

## Commit & Pull Request Guidelines

The current history is minimal and uses a short imperative-style subject, for example `scaffolding`. Keep commits concise, present-tense, and focused on one change. Pull requests should include a short summary, linked issue or spec reference when available, test results such as `./gradlew test`, and screenshots only for user-visible web changes.

## Configuration & Security Notes

Do not commit local secrets, credentials, or machine-specific paths. Keep environment-specific configuration outside source control or in ignored local overrides. Document new required environment variables in repository documentation when adding them.
