# Harvesters Phase 4: CLI and REST Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users install harvesters and create, run and manage sources through the CLI and REST API.

**Architecture:** The daemon owns every write: it installs harvesters, writes pins and secrets, and manages sources, through `HarvesterInstaller` and `HarvestSourceService` behind two new controllers. The CLI is a thin client over a new `HarvestClient`. Forms are driven by each harvester's manifest, so the console can reuse them in phase 8.

**Tech Stack:** Java 25, Spring Boot 4, picocli, Jackson 3, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/phases/phase-16-knowledge-harvesters.md`, sections "Security", "CLI and console", "Cost and volume" and "Amendments since approval".

## Global Constraints

- `./gradlew test` must pass before every commit. Never run `nativeCompile`, `nativeDist` or `deployLocal`.
- Work on branch `harvesters-phase-4`, never on `main`.
- Secret values never appear in any API response, log line, audit row, task result or command-line argument. Responses list secret names only.
- New wire DTOs go in `dev.alvo.pieria.api.request` and `.response`, which native builds register automatically.
- Source and harvester ids match `^[a-z0-9][a-z0-9_-]{0,63}$`.
- CLI exit codes follow `AbstractProfileCommand`: `0` ok, `1` error, `3` daemon unreachable, `4` not found.
- CLI tests point `--daemon-url` at a throwaway localhost HTTP stub. No test seams in production code.
- Test classes use the `*Tests` suffix. `Optional` is only a return type at a lookup boundary.

## Review Focus

1. A source is created with a secret: the value is absent from the audit trail, API responses and logs. Pinned in Task 4.
2. An install is pointed at a malicious package (zip-slip entry, symlink, zip bomb): it is rejected and nothing lands in the harvesters directory. Pinned in Task 2.
3. An install or replacement fails partway: the previously installed harvester and its pin are untouched. Pinned in Task 2.
4. A source is removed while its run is in progress: the run stops before its rows go, and nothing is left half-deleted. Pinned in Task 3.
5. Source config has a wrong type, unknown key, or a secret passed as plain config: rejected with a 400 and nothing is written. Pinned in Task 3.

---

### Task 1: Accepting sweeps, check, and stopping runs

**Files:**
- Create: `modules/daemon/src/main/resources/db/migration/V8__harvest_accept_sweep.sql`, `modules/daemon/src/main/java/dev/alvo/pieria/harvest/SourceCheckResult.java`
- Modify: `modules/daemon/.../harvest/HarvestStore.java`, `SqliteHarvestStore.java`, `HarvestRunService.java`, `HarvestScheduler.java`
- Test: extend `SqliteHarvestStoreTests`, `HarvestRunServiceTests`, `HarvestSchedulerTests`; `ScriptedHarvester`

**Interfaces:**
- Produces:
  - `record SourceCheckResult(boolean ok, ErrorClass errorClass, String message)`
  - `HarvestRunService.check(String sourceId) : SourceCheckResult`
  - On `HarvestStore`: `void setAcceptNextSweep(String sourceId, boolean accept)`, `boolean acceptNextSweep(String sourceId)`, `void updateSource(HarvestSource source)` (config, mode, interval, limits, cursor), `boolean anySourceUses(String harvesterId)`
  - On `HarvestScheduler`: `boolean isRunning(String sourceId)`, `boolean stopAndWait(String sourceId, Duration timeout)`

**Migration:** `ALTER TABLE harvest_sources ADD COLUMN accept_next_sweep INTEGER NOT NULL DEFAULT 0;`

**Rules:** a sweep applies regardless of the guard when `acceptNextSweep` is set, and the flag is cleared once applied. `check` launches the `check` subcommand with a 60-second deadline, requires the pin, saves a declared `secret_update`, scrubs secret values from its message, and treats anything but exit 0 with no `error` as not ok. An `auth` result doesn't change the source's status. `stopAndWait` returns true when nothing is running or the run ended within the timeout.

- [ ] **Step 1: Create the branch and write the failing tests**

Run: `git switch -c harvesters-phase-4`

`ScriptedHarvester.check` gains `config.checkFail` (`{class, message}`), and `config.checkRecords` for `secret_update`.

```java
// SqliteHarvestStoreTests
@Test void acceptNextSweepRoundTrips() { }
@Test void updateSourceChangesConfigAndLimits() { }
@Test void anySourceUsesFindsAHarvester() { }

// HarvestRunServiceTests
@Test void anAcceptedSweepBypassesTheGuardOnce() { /* 10 live, 7 reported: accepted run deletes 3; flag cleared; next blocked sweep is blocked */ }
@Test void acceptanceIsKeptUntilASweepHappens() { /* run with no sweep leaves the flag set */ }
@Test void checkReportsSuccess() { }
@Test void checkReportsTheHarvestersError() { /* class AUTH, message */ }
@Test void checkDoesNotPauseTheSource() { }
@Test void checkScrubsSecrets() { }
@Test void checkSavesARotatedSecret() { }
@Test void checkRefusesAChangedHarvester() { /* class CONFIG */ }
@Test void checkTimesOut() { /* a hanging check with a short deadline reports transient */ }

// HarvestSchedulerTests
@Test void isRunningReflectsAnInFlightRun() { }
@Test void stopAndWaitCancelsARunningSource() { }
@Test void stopAndWaitIsTrueWhenIdle() { }
@Test void stopAndWaitIsFalseWhenTheRunWillNotEnd() { /* a run ignoring cancellation, 200ms timeout */ }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :daemon:test --tests '*harvest.*'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement it**

`checkTimesOut` needs the deadline to be injectable through the real surface, so use `min(60s, properties.runTimeout())`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :daemon:test --tests '*harvest.*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src
git commit -m "Add sweep acceptance, source checks and run stopping"
```

---

### Task 2: Installing and removing harvesters

**Files:**
- Create under `modules/daemon/src/main/java/dev/alvo/pieria/harvest/`: `HarvesterInstaller.java`, `HarvesterPackage.java`, `HarvestPins.java`, `HarvesterDiagnosis.java`
- Modify: `HarvesterRegistry.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/harvest/HarvesterInstallerTests.java`, extend `HarvesterRegistryTests`

**Interfaces:**
- Consumes: `HarvestPaths`, `HarvestStore.anySourceUses`, `ManifestParser`.
- Produces:
  - `record HarvesterPackage(HarvesterManifest manifest, String entrypointSha256, String manifestSha256)`
  - `@Component class HarvesterInstaller`: `HarvesterPackage inspect(Path source)`, `HarvesterPackage install(Path source, String expectedEntrypointSha256, String expectedManifestSha256, boolean replace)`, `void remove(String id)`
  - `final class HarvestPins`: `HarvestPins(HarvestPaths paths)`, `JsonNode read()`, `void put(String id, String entrypointSha256, String manifestSha256)`, `void remove(String id)` (atomic write, synchronized)
  - `record HarvesterDiagnosis(String directory, boolean ok, String detail)` and `List<HarvesterDiagnosis> HarvesterRegistry.diagnose()`

**Rules:**
- `source` must be an absolute path to a directory or a `.zip` file. A zip with no root manifest but one top-level directory holding it is unpacked from that directory.
- Everything is prepared in a staging directory under `paths.root()` (a name starting with `.`, which the id pattern already excludes), and removed afterwards.
- Rejected with `IllegalArgumentException`: a symlink anywhere in a source directory; a zip entry that is absolute or has a `..` segment; more than 20,000 files or 256 MiB unpacked, measured while unpacking; a missing or invalid manifest; a missing entrypoint.
- Supplied expected hashes must match, or the install fails with `ConflictException`, which covers a package changed between inspect and install.
- An existing harvester without `replace` is a `ConflictException`. With `replace`, the old directory moves aside, the new one moves in, the pin is written, and the old one is deleted. A failure restores the old directory and pin.
- A `./` program is made executable after unpacking, since zip doesn't reliably carry the mode.
- `remove` throws `ConflictException` if any source uses the harvester, `NotFoundException` if absent, and deletes the directory and pin.
- `diagnose` reports each directory under the root: unparseable manifest, id mismatch, no pin, checksum mismatch, or a bare program not found on `PATH`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void installsADirectoryAndPinsIt() { /* registry.find present; pins.json holds both hashes */ }
@Test void installsAZip() { }
@Test void installsAZipWithOneTopLevelDirectory() { }
@Test @DisabledOnOs(OS.WINDOWS) void zipEntrypointBecomesExecutable() { }
@Test void inspectLeavesNothingBehind() { /* root has no new entries */ }
@Test void rejectsARelativePath() { }
@Test @DisabledOnOs(OS.WINDOWS) void rejectsASymlink() { }
@Test void rejectsZipSlip() { /* entry "../evil": nothing outside staging */ }
@Test void rejectsAnAbsoluteZipEntry() { }
@Test void rejectsAZipBomb() { /* highly compressible entry over the limit */ }
@Test void rejectsTooManyFiles() { }
@Test void rejectsAMissingManifest() { }
@Test void rejectsAMissingEntrypoint() { }
@Test void aChangedPackageFailsExpectedHashes() { }
@Test void refusesToOverwriteWithoutReplace() { }
@Test void replaceSwapsTheHarvesterAndPin() { }
@Test void aFailedReplaceKeepsTheOldHarvester() { /* expected-hash failure and a bad new package: old files and pin unchanged */ }
@Test void failedInstallLeavesNoStaging() { }
@Test void removeDeletesDirectoryAndPin() { }
@Test void removeRefusesAHarvesterInUse() { }
@Test void removingAnUnknownHarvesterIsNotFound() { }

// HarvesterRegistryTests
@Test void diagnoseReportsEachProblem() { /* unpinned, changed, broken manifest, id mismatch, ok */ }
@Test void diagnoseReportsAMissingInterpreter() { }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :daemon:test --tests '*HarvesterInstallerTests' --tests '*HarvesterRegistryTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement it**

Copy with `NOFOLLOW_LINKS`, preserving POSIX permissions. Count bytes as they are written rather than trusting the zip's declared sizes. `HarvesterRegistry` reads pins through `HarvestPins`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :daemon:test --tests '*HarvesterInstallerTests' --tests '*HarvesterRegistryTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src
git commit -m "Install, pin and remove harvesters"
```

---

### Task 3: Source management

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/harvest/HarvestSourceService.java`, `SourceConfigValidator.java`, `SourceDraft.java`, `SourceUpdate.java`
- Modify: `modules/daemon/.../profile/ProfileService.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/harvest/HarvestSourceServiceTests.java`, `SourceConfigValidatorTests.java`

**Interfaces:**
- Consumes: `HarvestStore`, `HarvesterRegistry`, `HarvestSecretStore`, `HarvestItemIngestor.delete`, `HarvestScheduler`, `HarvestRunService.check`.
- Produces:
  - `record SourceDraft(String id, String harvesterId, ObjectNode config, Map<String,String> secrets, SourceMode mode, long intervalSeconds, Integer maxItemsPerRun, Long maxCharsPerRun, boolean start)`
  - `record SourceUpdate(ObjectNode config, SourceMode mode, Long intervalSeconds, Integer maxItemsPerRun, Long maxCharsPerRun, boolean resetCursor)`, where nulls keep current values
  - `final class SourceConfigValidator`: `static void validate(HarvesterManifest manifest, ObjectNode config, Set<String> secretNames, boolean requireAll)`, throwing `IllegalArgumentException`
  - `@Service class HarvestSourceService`: `HarvestSource create(String profile, SourceDraft draft)`, `HarvestSource update(String profile, String id, SourceUpdate update)`, `HarvestSource get(String profile, String id)`, `List<HarvestSource> list(String profile)`, `void remove(String profile, String id, boolean forgetMemories)`, `SourceCheckResult check(String profile, String id)`, `void pause(String profile, String id)`, `void resume(String profile, String id)`, `void acceptNextSweep(String profile, String id)`, `void setSecrets(String profile, String id, Map<String,String> secrets)`, `UUID run(String profile, String id, boolean dryRun)`, `List<HarvestRun> runs(String profile, String id, int limit)`, `Set<String> secretNames(String id)`, `boolean isRunning(String id)`, `boolean usesRemoteModelProvider()`, `void removeAllFor(String profileId)`

**Rules:**
- A new source is `PAUSED` unless `start` is true, so nothing starts an expensive backfill before the user has checked it. `nextRunAt` is now.
- Validation: an unknown key, a key of kind `secret` in config, a wrong type (`int` integral, `bool` boolean, `enum` in options, `list` array of strings, `string` text), or a secret name the manifest doesn't declare is rejected. Required non-secret fields must be present, and required secrets must be supplied on create. Nothing is written when validation fails.
- A source in another profile, or an unknown one, is `NotFoundException`. A duplicate id across any profile is `ConflictException`. An unknown harvester is `IllegalArgumentException`. Defaults: `mode` `FULL`; minimum interval 60 seconds.
- `remove`: `stopAndWait` for 30 seconds, `ConflictException` if it won't stop. With `forgetMemories`, `ingestor.delete` runs for every item. Then delete the source and its secrets.
- `resume` sets `ACTIVE` and makes it due now, which also clears `NEEDS_AUTH`. `run` is allowed on a paused source; a busy source or full capacity is `ConflictException`.
- `update` with `resetCursor` clears the cursor. A running source can't be updated (`ConflictException`).
- `usesRemoteModelProvider` is true when `pieria.provider.base-url` has a host that isn't loopback.
- `ProfileService.delete` calls `removeAllFor(profileId)` first, through an `ObjectProvider<HarvestSourceService>`: it stops each source's run and removes its secrets.

- [ ] **Step 1: Write the failing tests**

```java
// SourceConfigValidatorTests
@Test void acceptsAValidConfig() { }
@Test void rejectsAnUnknownKey() { }
@Test void rejectsASecretInConfig() { }
@Test void rejectsWrongTypes() { /* one case per kind */ }
@Test void rejectsAValueOutsideEnumOptions() { }
@Test void rejectsAMissingRequiredField() { }
@Test void rejectsAMissingRequiredSecret() { }
@Test void rejectsAnUndeclaredSecret() { }
@Test void partialValidationSkipsRequiredChecks() { }

// HarvestSourceServiceTests: real store, registry and secrets; mock scheduler and run service
@Test void createsPausedByDefault() { }
@Test void startTrueIsActiveAndDue() { }
@Test void storesSecretsSeparatelyFromConfig() { /* configJson has no value */ }
@Test void invalidConfigWritesNothing() { /* no source row, no secret */ }
@Test void duplicateIdConflicts() { }
@Test void unknownHarvesterIsRejected() { }
@Test void intervalBelowTheMinimumIsRejected() { }
@Test void anotherProfilesSourceIsNotFound() { }
@Test void updateChangesConfig() { }
@Test void updateCanResetTheCursor() { }
@Test void updateRefusedWhileRunning() { }
@Test void removeStopsTheRunFirst() { /* InOrder: stopAndWait then deleteSource */ }
@Test void removeConflictsWhenARunWillNotStop() { /* nothing deleted */ }
@Test void removeKeepsMemoriesByDefault() { }
@Test void removeCanForgetMemories() { /* ingestor.delete for each item */ }
@Test void removeDeletesSecrets() { }
@Test void resumeClearsNeedsAuthAndMakesDue() { }
@Test void pausedSourceCanStillBeRun() { }
@Test void busySourceRunConflicts() { }
@Test void acceptSweepSetsTheFlag() { }
@Test void setSecretsRejectsUndeclaredNames() { }
@Test void secretNamesNeverIncludeValues() { }
@Test void loopbackProviderIsLocal() { }
@Test void remoteProviderIsReported() { }
@Test void removeAllForStopsAndClearsSecrets() { }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :daemon:test --tests '*SourceConfigValidatorTests' --tests '*HarvestSourceServiceTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement it**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :daemon:test --tests '*SourceConfigValidatorTests' --tests '*HarvestSourceServiceTests' --tests '*ProfileApiTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src
git commit -m "Manage harvest sources"
```

---

### Task 4: REST API

**Files:**
- Create in `modules/shared/src/main/java/dev/alvo/pieria/api/request/`: `HarvesterPathRequest.java`, `InstallHarvesterRequest.java`, `CreateSourceRequest.java`, `UpdateSourceRequest.java`, `SourceSecretsRequest.java`, `RunSourceRequest.java`
- Create in `.../api/response/`: `HarvesterResponse.java` (nested `Field`), `HarvesterListResponse.java`, `HarvesterPackageResponse.java`, `HarvesterDoctorResponse.java` (nested `Entry`), `SourceResponse.java`, `SourceListResponse.java`, `SourceRunResponse.java`, `SourceRunListResponse.java`, `SourceCheckResponse.java`
- Create: `modules/daemon/.../api/controller/HarvesterController.java`, `SourceController.java`
- Modify: `modules/daemon/.../audit/ProfileAuditFilter.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/api/HarvesterApiTests.java`, `SourceApiTests.java`, `modules/daemon/src/test/java/dev/alvo/pieria/audit/SourceAuditTests.java`

**Interfaces:**
- Consumes: Tasks 1 to 3.
- Produces:

| Method and path | Body | Result |
|---|---|---|
| `GET /v1/harvesters` | | `HarvesterListResponse` |
| `GET /v1/harvesters/doctor` | | `HarvesterDoctorResponse` |
| `POST /v1/harvesters/inspect` | `HarvesterPathRequest(path)` | `HarvesterPackageResponse` |
| `POST /v1/harvesters` | `InstallHarvesterRequest(path, entrypointSha256, manifestSha256, replace)` | 201 `HarvesterResponse` |
| `DELETE /v1/harvesters/{id}` | | 204 |
| `POST /v1/profiles/{name}/sources` | `CreateSourceRequest(id, harvester, config, secrets, mode, intervalSeconds, maxItemsPerRun, maxCharsPerRun, start)` | 201 `SourceResponse` |
| `GET .../sources`, `GET .../sources/{id}` | | `SourceListResponse`, `SourceResponse` |
| `PATCH .../sources/{id}` | `UpdateSourceRequest(config, mode, intervalSeconds, maxItemsPerRun, maxCharsPerRun, resetCursor)` | `SourceResponse` |
| `DELETE .../sources/{id}?forgetMemories=` | | 204 |
| `POST .../sources/{id}/check` | | `SourceCheckResponse(ok, errorClass, message)` |
| `POST .../sources/{id}/run` | `RunSourceRequest(dryRun)` | 202 `TaskSubmitResponse` |
| `GET .../sources/{id}/runs?limit=` | | `SourceRunListResponse` |
| `POST .../sources/{id}/pause`, `/resume`, `/accept-sweep` | | `SourceResponse` |
| `PUT .../sources/{id}/secrets` | `SourceSecretsRequest(secrets)` | 204 |

`SourceResponse(id, profile, harvester, config, secretNames, mode, intervalSeconds, maxItemsPerRun, maxCharsPerRun, status, nextRunAt, consecutiveFailures, sweepBlockedCount, acceptNextSweep, running, warnings, lastRun)`. `warnings` holds a message when the model provider is remote: `"Text from this source will be sent to your configured model provider."`

`ProfileAuditFilter`: `operation` maps `sources` paths to `source.create|list|read|update|delete|check|run|runs|pause|resume|accept-sweep|secrets`. Request and response bodies are not captured for any `sources` path, the same way `wiki` paths are handled.

- [ ] **Step 1: Write the failing tests**

`@WebMvcTest` slices with `@MockitoBean` services, as in `GraphApiTests`.

```java
// HarvesterApiTests
@Test void listsHarvesters() { /* fields, network, capabilities */ }
@Test void inspectReturnsHashes() { }
@Test void installReturns201() { }
@Test void installConflictIs409() { }
@Test void aBadPackageIs400() { }
@Test void removeReturns204() { }
@Test void removingOneInUseIs409() { }
@Test void doctorListsProblems() { }

// SourceApiTests
@Test void createReturnsTheSourceWithoutSecretValues() { /* secretNames present; response body contains no value */ }
@Test void createValidatesTheId() { /* 400 */ }
@Test void createReportsARemoteProviderWarning() { }
@Test void listAndGet() { }
@Test void patchUpdates() { }
@Test void deletePassesForgetMemories() { }
@Test void checkReportsFailure() { }
@Test void runReturnsATask() { /* 202 */ }
@Test void runningABusySourceIs409() { }
@Test void runsLists() { }
@Test void pauseResumeAndAcceptSweep() { }
@Test void secretsReturn204() { }
@Test void unknownSourceIs404() { }

// SourceAuditTests: real ProfileAuditFilter and a recording AuditRecorder
@Test void sourceBodiesAreNeverCaptured() { /* POST with a secret: recorded payload has no value */ }
@Test void sourceOperationsAreNamed() { /* ProfileAuditFilter.operation("POST", "sources/x/run") equals "source.run", one per operation */ }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :daemon:test --tests '*HarvesterApiTests' --tests '*SourceApiTests' --tests '*SourceAuditTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement the DTOs, controllers and audit handling**

`HarvesterController` calls `HarvesterRegistry` and `HarvesterInstaller`. `SourceController` calls `HarvestSourceService` only. `SourceRunResponse` uses the run's scrubbed fields.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :daemon:test --tests '*HarvesterApiTests' --tests '*SourceApiTests' --tests '*SourceAuditTests' --tests '*ApiContractTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/shared modules/daemon/src
git commit -m "Add harvester and source REST APIs"
```

---

### Task 5: `pieria harvester` commands

**Files:**
- Create: `modules/shared/src/main/java/dev/alvo/pieria/client/HarvestClient.java`
- Create under `modules/cli/src/main/java/dev/alvo/pieria/cli/command/harvest/`: `AbstractHarvestCommand.java`, `HarvesterCommand.java`, `HarvesterListCommand.java`, `HarvesterInstallCommand.java`, `HarvesterRemoveCommand.java`, `HarvesterDoctorCommand.java`
- Modify: `modules/cli/.../PieriaCli.java`
- Test: `modules/cli/src/test/java/dev/alvo/pieria/cli/command/harvest/HarvesterCommandTests.java`

**Interfaces:**
- Produces: `final class HarvestClient` mirroring every Task 4 route, with typed requests and responses; `abstract class AbstractHarvestCommand implements Callable<Integer>` with `--daemon-url` and `protected abstract int run(HarvestClient client) throws Exception`, using the exit-code mapping from `AbstractProfileCommand`.

**Commands:** `pieria harvester list`; `doctor` (exit 1 when any entry is not ok); `remove <id>`; `install <dir|zip> [--replace] [--yes]`. Install resolves the path to absolute, calls inspect, prints name, version, command, network hosts and both hashes, and states that harvesters run with the user's permissions and are not sandboxed. It then asks for confirmation unless `--yes`, and installs with the inspected hashes. Without a console and without `--yes`, it refuses with exit 1.

- [ ] **Step 1: Write the failing tests**

```java
@Test void listPrintsHarvesters() { }
@Test void doctorExitsOneOnAProblem() { }
@Test void doctorExitsZeroWhenHealthy() { }
@Test void installShowsTheManifestAndSendsTheInspectedHashes() { /* --yes; stub records the install body */ }
@Test void installWarnsItIsNotSandboxed() { }
@Test void installRefusesWithoutConfirmation() { /* no --yes, no console: exit 1, install route not called */ }
@Test void installSendsAnAbsolutePath() { }
@Test void installPassesReplace() { }
@Test void removeCallsTheDaemon() { }
@Test void unknownHarvesterExitsFour() { }
@Test void unreachableDaemonExitsThree() { }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :cli:test --tests '*HarvesterCommandTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement the client and commands**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :cli:test --tests '*HarvesterCommandTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/shared modules/cli
git commit -m "Add the pieria harvester commands"
```

---

### Task 6: `pieria source` commands

**Files:**
- Create under `modules/cli/.../command/harvest/`: `SourceCommand.java`, `SourceAddCommand.java`, `SourceListCommand.java`, `SourceShowCommand.java`, `SourceUpdateCommand.java`, `SourceCheckCommand.java`, `SourceRunCommand.java`, `SourceRunsCommand.java`, `SourcePauseCommand.java`, `SourceResumeCommand.java`, `SourceRemoveCommand.java`, `SourceAcceptSweepCommand.java`, `SourceSecretCommand.java`, `SourceOptions.java`
- Modify: `modules/cli/.../PieriaCli.java`
- Test: `modules/cli/src/test/java/dev/alvo/pieria/cli/command/harvest/SourceCommandTests.java`, `SourceOptionsTests.java`

**Interfaces:**
- Consumes: `HarvestClient`, `TaskClient`, `ProfileResolver` (as the other commands use it for the working directory).
- Produces: `final class SourceOptions`: `static long parseInterval(String)` (`90`, `30m`, `6h`, `1d`), `static ObjectNode parseConfig(List<String> settings, HarvesterResponse harvester)` (`key=value` converted by the field's kind; lists split on commas).

**Commands:** every command takes `--profile`, defaulting to the resolved profile for the working directory.

- `source add <harvester> --id <id> [--set key=value]... [--secret-env name=ENV]... [--interval 6h] [--mode full|raw] [--max-items n] [--max-chars n] [--start]`. Secret values come only from `--secret-env` or a hidden console prompt, never a flag value. Missing required fields are prompted for on a console; without one they are an error. After creating, it prints any warnings, runs `check`, and prints the next steps: `pieria source run <id> --dry-run` and `pieria source resume <id>`. Default interval `6h`.
- `source list`, `show <id>`, `runs <id> [--limit 10]`, `check <id>`, `pause <id>`, `resume <id>`, `accept-sweep <id>`.
- `source update <id> [--set key=value]... [--interval] [--mode] [--max-items] [--max-chars] [--reset-cursor]`.
- `source secret <id> --secret-env name=ENV` (or prompts for `name` when given `--name`).
- `source run <id> [--dry-run] [--wait]`. A dry run always waits, polling the task, then prints items, changed items, chars and estimated chunks. A blocked sweep is shown in `show` with the accept command.
- `source remove <id> [--forget-memories] [--yes]`, confirming when interactive.

- [ ] **Step 1: Write the failing tests**

```java
// SourceOptionsTests
@Test void parsesIntervals() { /* 90, 30m, 6h, 1d */ }
@Test void rejectsABadInterval() { }
@Test void convertsConfigByKind() { /* int, bool, list, enum, string */ }
@Test void rejectsAnUnknownKey() { }
@Test void rejectsAMalformedSetting() { }

// SourceCommandTests
@Test void addSendsConfigAndSecretsFromTheEnvironment() { }
@Test void addRefusesAMissingRequiredFieldWithoutAConsole() { }
@Test void addHasNoOptionThatTakesASecretValue() { /* picocli model lists no --secret option */ }
@Test void addPrintsWarningsAndNextSteps() { }
@Test void addRunsCheckAndReportsFailure() { /* source stays; exit 1; explains */ }
@Test void addDefaultsToAPausedSource() { }
@Test void showFlagsABlockedSweep() { }
@Test void runSubmitsATask() { }
@Test void dryRunWaitsAndPrintsCounts() { }
@Test void updateSendsOnlyWhatChanged() { }
@Test void pauseResumeAndAcceptSweepCallTheDaemon() { }
@Test void removePassesForgetMemories() { }
@Test void removeRefusesWithoutConfirmation() { }
@Test void secretReadsTheEnvironment() { }
@Test void missingEnvironmentVariableIsAnError() { /* never sends an empty secret */ }
@Test void unknownSourceExitsFour() { }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :cli:test --tests '*SourceCommandTests' --tests '*SourceOptionsTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement the commands**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :cli:test --tests '*SourceCommandTests' --tests '*SourceOptionsTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/cli
git commit -m "Add the pieria source commands"
```

---

### Task 7: Recall filters, labels and documents

**Files:**
- Modify: `modules/gateway/.../mcp/MemoryTools.java`, `modules/cli/.../command/profile/ProfileRecallCommand.java`, `ProfileMemoriesCommand.java`
- Modify: `README.md`, `AGENTS.md`, `docs/phases/phase-16-knowledge-harvesters.md`
- Test: extend `modules/gateway/src/test/java/dev/alvo/pieria/mcp/MemoryToolsTests.java`; `modules/cli/src/test/java/dev/alvo/pieria/cli/command/ProfileRecallCommandTests.java`

**Interfaces:**
- Consumes: `RecallRequest` eight-argument constructor, `MemoryResponse.origin()`, `MemoryOrigin.renderLabel()`.
- Produces: the MCP `recall` tool gains optional `includeSources` and `excludeSources` (lists of source ids); the existing overloads stay. `pieria profile recall` gains repeatable `--source` and `--exclude-source`.

Harvested memories print with their label: `- [fact] [untrusted · jira · ACME-12 · url] text` in recall, and on the line under the id in `profile memories`.

- [ ] **Step 1: Write the failing tests**

```java
// MemoryToolsTests
@Test void recallPassesSourceFilters() { }
@Test void recallFiltersDefaultToEmpty() { }

// ProfileRecallCommandTests
@Test void passesSourceFilters() { /* stub records includeSources and excludeSources */ }
@Test void labelsHarvestedEvidence() { }
@Test void leavesOwnMemoriesUnlabelled() { }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :gateway:test --tests '*MemoryToolsTests' :cli:test --tests '*ProfileRecallCommandTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement the filters and labels**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :gateway:test --tests '*MemoryToolsTests' :cli:test --tests '*ProfileRecallCommandTests'`
Expected: PASS

- [ ] **Step 5: Update the documents**

- `README.md`: a "Knowledge harvesters" section with the install, add, dry-run and resume flow, and the `includeSources` / `excludeSources` recall parameters.
- `AGENTS.md` REST table: add the harvester and source routes in one line, pointing to the phase 16 doc.
- Phase 16 doc: status to phases 1 to 4 complete, and amendments: new sources start paused; `.zip` is the only archive format in v1; `source update` and `source secret` were added to the planned commands; `harvester install --replace` covers upgrades.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add modules README.md AGENTS.md docs/phases/phase-16-knowledge-harvesters.md docs/superpowers/plans/2026-11-09-harvester-cli-rest.md
git commit -m "Add source filters and labels to recall clients"
```
