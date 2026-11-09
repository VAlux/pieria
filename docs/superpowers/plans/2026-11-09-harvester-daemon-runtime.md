# Harvesters Phase 3: Daemon Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the daemon run installed harvesters on a schedule and keep memories in step with their sources, including edits and deletions.

**Architecture:** A new `dev.alvo.pieria.harvest` package holds the store, registry, secrets, item ingestor, run service and scheduler. The process plumbing moves from the conformance kit into a public SDK class, so the daemon and the kit launch harvesters the same way. There is still no REST or CLI surface; phase 4 adds that on top of these services.

**Tech Stack:** Java 25, Spring Boot 4, SQLite + Flyway, `:harvester-sdk`, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/phases/phase-16-knowledge-harvesters.md`, sections "Daemon runtime", "Item ingestion", "Cost and volume", "Security", and "Amendments since approval".

## Global Constraints

- `./gradlew test` must pass before every commit. Never run `nativeCompile`, `nativeDist` or `deployLocal`.
- Work on branch `harvesters-phase-3`, never on `main`.
- Harvested content is always ingested with an `IngestOrigin(sourceId, harvesterId, externalId, url)`.
- Item session id: `harvest:<sourceId>:<Hash.hash128(externalId)>`.
- A cursor is persisted only after every earlier record is durably processed.
- `sweep` takes effect only after exit 0 with no `error` record.
- Mass-delete guard: nothing is deleted when `unseen > max(floor, share * live)`, with `share = 0.2` and `floor = 2`.
- Secrets reach a harvester only through stdin, and only those its manifest declares as `secret`. The API, logs, audit and export never contain them.
- Properties prefix `pieria.harvest` in a standalone `HarvestProperties` record, following `TraceProperties`.
- JSON goes through Jackson's tree model. Test classes use the `*Tests` suffix, model calls use `FakeModelGateway`, and production code gets no test seams.
- `Optional` is only a return type at a lookup boundary.

## Review Focus

1. A harvester emits `sweep` and then crashes or exits non-zero: nothing is deleted. Pinned in Task 5.
2. The daemon restarts mid-run: the run is marked interrupted and the source resumes from its last checkpoint, not from the start. Pinned in Task 6.
3. The user revised a harvested memory and the source item is then edited: the user's revision is left alone. Pinned in Task 4.
4. An item is deleted at the source and later reappears: it is ingested again from scratch, with no stale state skipping extraction. Pinned in Task 4.
5. A harvester's executable changes after install, or a harvester sends `secret_update` for a name it didn't declare: the run refuses to launch, and the update is ignored. Pinned in Tasks 3 and 5.

---

### Task 1: Shared process launcher in the SDK

**Files:**
- Create: `modules/harvester-sdk/src/main/java/dev/alvo/pieria/harvester/process/HarvesterProcess.java`
- Modify: `modules/harvester-sdk/src/main/java/dev/alvo/pieria/harvester/conformance/ProcessRun.java` (rebuild on `HarvesterProcess`)
- Modify: `modules/daemon/build.gradle.kts` (`implementation(project(":harvester-sdk"))`)
- Test: `modules/harvester-sdk/src/test/java/dev/alvo/pieria/harvester/process/HarvesterProcessTests.java`

**Interfaces:**
- Produces, in `dev.alvo.pieria.harvester.process`, `final class HarvesterProcess implements AutoCloseable`:
  - `static HarvesterProcess start(List<String> baseCommand, String subcommand, Path workingDir, HarvestRequest request) throws IOException`
  - `HarvestRecord next(Instant deadline) throws TimeoutException, InterruptedException`: the next record, or null at clean end of output; throws `ProtocolViolation`
  - `boolean awaitExit(Instant deadline) throws InterruptedException`, `int exitCode()` (`-1` while alive), `boolean isAlive()`
  - `String stderr()`: captured, capped at 64 KiB
  - `void closeOutput() throws IOException`, `void destroy()` (process tree), `void close()` (same as `destroy`)

**Behaviour:** the environment is cleared except `PATH`, `HOME`, `SystemRoot`, `USERPROFILE`, `TEMP`, `TMP`, `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` and their lower-case forms. Records are read by a virtual thread only when `next` asks for one, so a slow consumer leaves the harvester blocked on a full pipe, and `closeOutput` never races a read. At the deadline `next` throws `TimeoutException` and abandons that thread, which covers a descendant holding stdout open.

- [ ] **Step 1: Create the branch and write the failing tests**

Run: `git switch -c harvesters-phase-3`

Launch the existing `FixtureHarvester` as in `ConformanceKitTests`.

```java
@Test void readsRecordsThenNullAtEndOfOutput() { /* "ok": records until null; awaitExit true; exitCode 0 */ }
@Test void violationSurfacesFromNext() { /* "garbage-stdout": assertThatThrownBy(next).isInstanceOf(ProtocolViolation.class) */ }
@Test void timesOutRatherThanBlocking() { /* "hangs": next(now + 500ms) throws TimeoutException within 3s */ }
@Test @DisabledOnOs(OS.WINDOWS) void descendantHoldingStdoutCannotBlockTheDeadline() { /* "orphan-holds-stdout": read to deadline, TimeoutException */ }
@Test void capturesStderrWithoutBlocking() { /* "leaks-secret": stderr() contains the secret after exit */ }
@Test void doesNotReadAheadOfTheConsumer() { /* "ok": after one next(), closeOutput() returns promptly and no exception */ }
@Test void destroyEndsTheProcess() { /* "hangs": destroy(); awaitExit true within 5s */ }
@Test void environmentIsScrubbed() { /* a new "prints-env" fixture mode writes System.getenv keys to stderr; assert only allowed names */ }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :harvester-sdk:test --tests '*HarvesterProcessTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement it and rebuild `ProcessRun` on it**

`ProcessRun` keeps its `Outcome` and `CloseResult` and loses its own launch, read and deadline code.

- [ ] **Step 4: Run the SDK tests to verify they pass**

Run: `./gradlew :harvester-sdk:test`
Expected: PASS, including every existing conformance test.

- [ ] **Step 5: Commit**

```bash
git add modules/harvester-sdk modules/daemon/build.gradle.kts
git commit -m "Share harvester process handling through the SDK"
```

---

### Task 2: Schema and store

**Files:**
- Create: `modules/daemon/src/main/resources/db/migration/V7__harvest.sql`
- Create under `modules/daemon/src/main/java/dev/alvo/pieria/harvest/`: `HarvestStore.java`, `SqliteHarvestStore.java`, `HarvestSource.java`, `SourceMode.java`, `SourceStatus.java`, `HarvestItem.java`, `MemoryLink.java`, `HarvestRun.java`, `RunStatus.java`
- Modify: `modules/daemon/.../storage/SqliteMemoryStore.java` (`deleteProfile`; `deleteSessionMessages`; `clearChunkLedger`), `MemoryStore.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/harvest/SqliteHarvestStoreTests.java`

**Interfaces:**
- Produces, in `dev.alvo.pieria.harvest`:
  - `record HarvestSource(String id, String profileId, String harvesterId, String configJson, SourceMode mode, long intervalSeconds, Integer maxItemsPerRun, Long maxCharsPerRun, SourceStatus status, String cursorJson, Instant nextRunAt, int consecutiveFailures, Long sweepBlockedCount, Instant createdAt)`
  - `enum SourceMode { FULL, RAW }`, `enum SourceStatus { ACTIVE, PAUSED, NEEDS_AUTH }`, `enum RunStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }`
  - `record HarvestItem(String sourceId, String externalId, String sessionId, String versionHash, String prefixHash, int turnCount, String lastSeenRun)`
  - `record MemoryLink(String memoryId, String assertionId, String revisionId)`
  - `record HarvestRun(String id, String sourceId, boolean dryRun, RunStatus status, Instant startedAt, Instant finishedAt, long itemsSeen, long itemsChanged, long itemsDeleted, long memoriesStored, long chars, String errorClass, String errorMessage, String stderrTail)`
  - `interface HarvestStore` with: `void createSource(HarvestSource)`, `Optional<HarvestSource> findSource(String)`, `List<HarvestSource> listSources(String profileId)`, `List<HarvestSource> dueSources(Instant now, int limit)` (`ACTIVE` only), `void saveCursor(String sourceId, String cursorJson)`, `void scheduleNext(String sourceId, Instant nextRunAt, int consecutiveFailures)`, `void setStatus(String sourceId, SourceStatus)`, `void setSweepBlocked(String sourceId, Long count)`, `void deleteSource(String sourceId)`; `String startRun(String sourceId, boolean dryRun)`, `void finishRun(HarvestRun)`, `int interruptRunningRuns(Instant nextRunAt)`, `List<HarvestRun> listRuns(String sourceId, int limit)`; `Optional<HarvestItem> findItem(String sourceId, String externalId)`, `void upsertItem(HarvestItem)`, `void markSeen(String sourceId, Collection<String> externalIds, String runId)`, `List<HarvestItem> unseenItems(String sourceId, String runId)`, `long liveItemCount(String sourceId)`, `void deleteItem(String sourceId, String externalId)`; `void addLinks(String sourceId, String externalId, List<MemoryLink>)`, `List<MemoryLink> links(String sourceId, String externalId)`, `void removeLinks(String sourceId, String externalId, Collection<String> memoryIds)`; `void deleteProfile(String profileId)`
  - On `MemoryStore`: `default void deleteSessionMessages(String profileId, String sessionId)` and `default void clearChunkLedger(String profileId, String sessionId)`, both throwing `UnsupportedOperationException` by default like their neighbours

**Schema:** `harvest_sources` keyed by `id`, with `profile_id`, an index on `(status, next_run_at)`, and `sweep_blocked_count`. `harvest_runs` keyed by `id`, indexed on `(source_id, started_at)`. `harvest_items` keyed by `(source_id, external_id)`, indexed on `(source_id, last_seen_run)`. `harvest_item_memories` keyed by `(source_id, external_id, memory_id, assertion_id)`. Timestamps are ISO-8601 text, as elsewhere.

`clearChunkLedger` deletes `ingest_ledger` rows with scope `chunk` whose `item_key` starts with `<sessionId>#`, using `substr` rather than `LIKE`, because source ids can contain `_`. `deleteSessionMessages` deletes the session's rows from `messages`; the existing trigger keeps `messages_fts` in step.

- [ ] **Step 1: Write the failing tests**

Real migrated SQLite, set up as in `SqliteMemoryStoreSearchTests`.

```java
@Test void createsAndFindsASource() { }
@Test void dueSourcesAreActiveAndPastTheirTime() { /* a PAUSED, a NEEDS_AUTH and a future source are excluded */ }
@Test void savedCursorIsReturned() { }
@Test void finishRunRecordsCounts() { }
@Test void interruptRunningRunsMarksThemAndMakesSourcesDue() { /* RUNNING -> INTERRUPTED; source nextRunAt set; return count */ }
@Test void upsertItemReplaces() { }
@Test void markSeenAndUnseenItems() { /* 3 items, 2 marked for run r: unseenItems returns the third */ }
@Test void linksRoundTripAndRemove() { }
@Test void deleteSourceRemovesItsRunsItemsAndLinks() { }
@Test void deleteProfileRemovesHarvestRows() { /* via SqliteMemoryStore.deleteProfile */ }
@Test void deleteSessionMessagesRemovesThemFromFullTextSearch() { /* searchMemoriesByMessageFts no longer matches */ }
@Test void clearChunkLedgerAffectsOnlyThatSession() { /* keys "s_1#0" and "sx1#0": clearing s_1 keeps sx1 */ }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :daemon:test --tests '*SqliteHarvestStoreTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement the migration and store**

`SqliteHarvestStore` is a `@Repository` over `JdbcClient`, like `SqliteCodeIndexStore`. `SqliteMemoryStore.deleteProfile` calls `new SqliteHarvestStore(jdbc).deleteProfile(profileId)` beside the cluster and wiki calls.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :daemon:test --tests '*SqliteHarvestStoreTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src
git commit -m "Add harvest tables and store"
```

---

### Task 3: Properties, registry and secrets

**Files:**
- Create under `modules/daemon/src/main/java/dev/alvo/pieria/harvest/`: `HarvestProperties.java` (in `dev.alvo.pieria.config`), `HarvesterRegistry.java`, `RegisteredHarvester.java`, `HarvestSecretStore.java`, `HarvestPaths.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/harvest/HarvesterRegistryTests.java`, `HarvestSecretStoreTests.java`

**Interfaces:**
- Consumes: `ManifestParser`, `HarvesterManifest.entrypointFile` (SDK), `Hash.sha256Hex(Path)`, `AppDataPathResolver`.
- Produces:
  - `record HarvestProperties(boolean enabled, long pollMs, int maxConcurrentRuns, int extractionConcurrency, Duration runTimeout, double sweepGuardShare, int sweepGuardFloor, Duration maxBackoff, String root)`, prefix `pieria.harvest`, defaults `true`, `30000`, `1`, `1`, `PT6H`, `0.2`, `2`, `PT24H`, `""`
  - `final class HarvestPaths`: `HarvestPaths(Path root)` with `root()`, `harvesterDir(String id)`, `pinsFile()` (`pins.json`), `secretsFile()` (`secrets.json`). Blank `root` resolves to `<app-data root>/harvesters`
  - `record RegisteredHarvester(HarvesterManifest manifest, Path directory)` with `List<String> command()`
  - `class HarvesterRegistry`: `List<RegisteredHarvester> list()`, `Optional<RegisteredHarvester> find(String id)`, `void requirePinned(RegisteredHarvester)` (throws `HarvesterIntegrityException`). No caching: each call reads the disk, so adding or removing a harvester needs no restart
  - `class HarvestSecretStore`: `Map<String,String> secrets(String sourceId)`, `void put(String sourceId, String name, String value)`, `void remove(String sourceId)`

**Rules:** a harvester is listed only when its directory name equals its manifest id, its manifest parses, and `pins.json` holds its entrypoint's SHA-256 matching the file. A bad entry is logged and skipped so one broken plugin can't hide the others. `secrets.json` is written atomically via a temp file and move, with mode `0600` where POSIX permissions exist.

**Spec amendment, recorded in Task 7:** harvesters live under the app-data root rather than `$PIERIA_HOME`, because `$PIERIA_HOME` is the install directory that updates replace.

- [ ] **Step 1: Write the failing tests**

```java
// HarvesterRegistryTests (temp root; write harvester.toml, entrypoint file and pins.json)
@Test void listsAPinnedHarvester() { }
@Test void skipsAnUnpinnedHarvester() { }
@Test void skipsAChangedEntrypoint() { /* pin, then rewrite the file: list() empty, find() empty */ }
@Test void requirePinnedRejectsAChangeAfterFind() { /* find, rewrite, requirePinned throws HarvesterIntegrityException */ }
@Test void skipsADirectoryWhoseNameDiffersFromItsId() { }
@Test void oneBrokenManifestDoesNotHideOthers() { }
@Test void missingRootIsEmpty() { }
@Test void seesNewHarvestersWithoutRestart() { }

// HarvestSecretStoreTests
@Test void storesPerSource() { }
@Test void updateKeepsOtherNames() { }
@Test void removeDeletesASourcesSecrets() { }
@Test void missingFileIsEmpty() { }
@Test @DisabledOnOs(OS.WINDOWS) void fileIsPrivate() { /* PosixFilePermissions.toString(...) equals "rw-------" */ }
@Test void survivesConcurrentWrites() { /* 20 virtual threads each put a distinct name; all 20 present */ }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :daemon:test --tests '*HarvesterRegistryTests' --tests '*HarvestSecretStoreTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement them**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :daemon:test --tests '*HarvesterRegistryTests' --tests '*HarvestSecretStoreTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src
git commit -m "Add the harvester registry and secret store"
```

---

### Task 4: Item ingestor with edit and delete handling

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/harvest/HarvestItemIngestor.java`, `ItemOutcome.java`
- Modify: `modules/daemon/.../ingestion/IngestionService.java` (concurrency override)
- Modify: `modules/daemon/.../onboarding/ContentIngestor.java` (make `toMessageContents` and `hardSplit` public, and `MAX_MESSAGE_CHARS` public)
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/harvest/HarvestItemIngestorTests.java`

**Interfaces:**
- Consumes: `HarvestStore`, `MemoryLink`, `HarvestItem`, `MemoryStore.deleteSessionMessages`, `MemoryStore.clearChunkLedger` (Task 2); `HarvestProperties` (Task 3); `HarvestRecord.Item` (SDK).
- Produces:
  - `IngestionResult IngestionService.ingestDetailed(String profileName, String sessionId, List<Message> messages, Integer extractionSamplesOverride, GraphMode graphMode, ChunkLedgerMode chunkLedgerMode, IngestProgressListener progress, IngestOrigin origin, Integer maxConcurrencyOverride)`. The 8-argument overload delegates with null.
  - `record ItemOutcome(boolean changed, int memoriesStored, long chars, String sessionId)`
  - `@Component class HarvestItemIngestor`: `ItemOutcome ingest(HarvestSource source, String profileName, HarvestRecord.Item item, String runId, IngestProgressListener progress)`, `ItemOutcome preview(HarvestSource source, HarvestRecord.Item item)` (reads state, writes nothing, no model), `void delete(HarvestSource source, String externalId)`

**Rules:**

- **Messages.** A document uses `ContentDocument("<harvester> — <title> (<url>)", text)` through `ContentIngestor.toMessageContents`, with role `document` and `createdAt = updatedAt`. A thread starts with a role `source` message `"<harvester> thread — <title> (<url>)"` at the first turn's time, then one message per turn using `MemoryOrigin.oneLine(author)` as role and the turn's timestamp, with oversized turns split by `hardSplit` and blank turns dropped.
- **Hashes.** `versionHash = Hash.hash128("harvest-v1", mode, title, url, content...)` where content is the document text, or each turn's id, author, timestamp and text. `prefixHash` hashes the first `turnCount` turns the same way.
- **Paths:**

| Case | Action |
|---|---|
| Same version hash | Mark seen only; no model call |
| New or edited document | Remove stale messages, full re-extract with `ChunkLedgerMode.DISABLED`, then retire |
| New thread, or a thread with an edited or removed earlier turn | Clear the chunk ledger, remove stale messages, ingest with `ENABLED`, then retire |
| Thread with only new turns (stored prefix hash matches) | Ingest with `ENABLED`, add links, retire nothing |

- **Retiring on edit.** Each linked memory the new version did not produce gets `MemoryStore.revise(profileId, memoryId, new RevisionRequest(assertionId, revisionId, null, null, null, invalidateAt))`, where `invalidateAt` is `updatedAt` or the latest turn's timestamp. A `TemporalConflictException` means the user has revised it: keep it, drop the link, and carry on.
- **Links.** Recorded after ingest from `MemoryStore.revisions`, taking the revisions with `recordedTo == null` and not `invalidated`.
- **Raw mode.** `IngestionService.preStageMessages` only, with no links and no model calls.
- **Delete.** `forgetMemory` for each link, `deleteSessionMessages`, `clearChunkLedger`, `deleteItem`.
- **Concurrency.** `maxConcurrencyOverride = properties.extractionConcurrency()`. `null` keeps the profile's value.

- [ ] **Step 1: Write the failing tests**

Real SQLite, real `IngestionService`, `FakeModelGateway`.

```java
@Test void newDocumentStoresGovernedMemoriesAndLinks() { /* changed; links present; origin stamped with externalId */ }
@Test void timestampsAnchorToTheSource() { /* stated_at equals updatedAt, not now */ }
@Test void threadTurnsCarryAuthorAndTimestamp() { /* raw message role "Ann", createdAt = turn time */ }
@Test void unchangedItemMakesNoModelCall() { /* counting gateway stays at the same call count */ }
@Test void titleChangeIsAnEdit() { }
@Test void editRetiresOnlyWhatTheNewVersionDidNotProduce() {
  // v1 then v2: the v1 memory is absent from exactKeyLookup/active, the v2 memory is present
}
@Test void retiredMemoryStaysVisibleInHistory() { /* store.atSnapshot(asOf before invalidateAt) still finds it */ }
@Test void editRemovesStaleRawMessages() { /* searchMemoriesByMessageFts for a v1-only word is empty */ }
@Test void appendedTurnsExtractOnlyNewChunks() { /* second ingest processes fewer chunks; earlier links remain */ }
@Test void editedEarlyTurnReplacesTheThread() { /* old memories retired, prefix hash updated */ }
@Test void userRevisedMemoryIsLeftAloneOnEdit() {
  // revise the memory's content first; edit the item; the revised memory is still active
}
@Test void deleteForgetsMemoriesAndRawText() { }
@Test void reappearingItemIsExtractedAgain() {
  // ingest thread, delete, ingest the same thread again: memories stored again
}
@Test void rawModeStagesTextWithoutTheModel() { /* zero model calls; message FTS matches; no links */ }
@Test void rawToFullIsAnEdit() { }
@Test void previewWritesNothing() { /* no item row, no messages, no model calls */ }
@Test void authorWithNewlinesIsFlattened() { }
@Test void hugeTurnIsSplit() { /* a 50,000 char turn yields messages each within MAX_MESSAGE_CHARS */ }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :daemon:test --tests '*HarvestItemIngestorTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement the ingestor and overload**

The override replaces `tuning.maxExtractionConcurrency()` at both call sites that drive extraction and verification when non-null.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :daemon:test --tests '*HarvestItemIngestorTests' --tests '*IngestionServiceTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src
git commit -m "Ingest harvested items and follow edits and deletes"
```

---

### Task 5: Run service

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/harvest/HarvestRunService.java`, `HarvestRunSummary.java`, `HarvesterIntegrityException.java` (if not added in Task 3)
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/harvest/HarvestRunServiceTests.java`, `ScriptedHarvester.java`

**Interfaces:**
- Consumes: Tasks 1 to 4.
- Produces:
  - `record HarvestRunSummary(String runId, RunStatus status, long itemsSeen, long itemsChanged, long itemsDeleted, long memoriesStored, long chars, long estimatedChunks, boolean sweepApplied, Long sweepBlockedCount, ErrorClass errorClass, String errorMessage, Long retryAfterSeconds, List<String> sessions)`
  - `@Service class HarvestRunService`: `HarvestRunSummary run(String sourceId, boolean dryRun, TaskProgress progress)`

**Run sequence:**

1. Load the source. Unknown source is `IllegalArgumentException`. Unknown or unpinned harvester fails the run with class `config`, with no launch. `requirePinned` runs immediately before launch.
2. Build the envelope: config, only the secrets declared `secret` in the manifest, cursor, limits. Launch through `HarvesterProcess` with deadline `now + runTimeout`.
3. Process each record synchronously, checking `progress.checkCancelled()` first:
   - `Document` or `ConversationThread`: ingest (`preview` on dry runs), then mark seen.
   - `Seen`: mark seen (not on dry runs).
   - `Tombstone`: delete (not on dry runs).
   - `Checkpoint`: `saveCursor` (not on dry runs).
   - `SecretUpdate`: store when the name is declared `secret`, including on dry runs, because the provider has already rotated the token. Otherwise warn and ignore.
   - `Sweep`: remember it.
   - `Progress`: update the task lane.
   - `Failure`: remember it.
4. After end of output, await exit before the deadline. A run succeeded only with exit 0 and no `Failure`.
5. On success with a remembered sweep and not a dry run, apply the guard: `unseen = unseenItems(source, runId)`, `live = liveItemCount(source)`. Over the limit: delete nothing, `setSweepBlocked(source, unseen.size())`. Otherwise delete each and `setSweepBlocked(source, null)`.
6. On failure, including a `ProtocolViolation`, timeout (`transient`), or non-zero exit with no `error` (`fatal`): checkpoints already saved stand and nothing is swept. `AUTH` sets status `NEEDS_AUTH`.
7. Always `finishRun` with secret values scrubbed from `errorMessage` and `stderrTail` (last 4,000 characters), and destroy the process. Cancellation records `CANCELLED` and rethrows `TaskCancelledException`.

`estimatedChunks` is `ceil(chars / chunk size)` using the first `PieriaProperties.Ingestion` component via `EffectiveConfigResolver`.

`ScriptedHarvester` is a test-source `main` using `HarvesterRunner.main`, which plays back `config.records` (an array of protocol JSON objects), then honours `config.failWith`, `config.exit` and `config.rawLine`. Each test installs it in a temp directory with a `run.sh` wrapper, manifest and pin, so the real registry and process path are exercised. These tests are `@DisabledOnOs(OS.WINDOWS)`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void ingestsItemsAndSavesTheCursor() { }
@Test void resumesFromTheSavedCursor() { /* the harvester echoes request.cursor into a document's text */ }
@Test void onlyDeclaredSecretsAreSent() { /* store two secrets; manifest declares one; echoed envelope has one */ }
@Test void checkpointsFromAFailedRunStand() { /* doc, checkpoint, error: cursor saved, status FAILED */ }
@Test void noCheckpointKeepsThePreviousCursor() { }
@Test void sweepDeletesUnseenItems() { }
@Test void sweepIsIgnoredWhenTheRunFails() { /* doc, sweep, exit 1: nothing deleted */ }
@Test void sweepIsIgnoredWithExitZeroAndError() { }
@Test void seenProtectsAnItemFromSweep() { }
@Test void massDeleteIsBlockedAndFlagged() { /* 10 live, sweep reports 7: nothing deleted, sweepBlockedCount 3 */ }
@Test void smallDeletesAreBelowTheGuardFloor() { /* 3 live, sweep reports 1: both deleted */ }
@Test void aCleanSweepClearsTheFlag() { }
@Test void tombstoneDeletesAnItem() { }
@Test void authErrorPausesTheSource() { /* status NEEDS_AUTH, errorClass AUTH */ }
@Test void retryAfterIsReported() { }
@Test void protocolViolationFailsTheRun() { /* rawLine garbage: FAILED, process gone */ }
@Test void timeoutFailsAsTransient() { /* runTimeout 2s, harvester hangs */ }
@Test void changedEntrypointPreventsLaunch() { /* FAILED class CONFIG; harvester never ran */ }
@Test void unknownHarvesterFailsTheRun() { }
@Test void rotatedSecretIsSaved() { }
@Test void undeclaredSecretUpdateIsIgnored() { }
@Test void secretsAreScrubbedFromTheRunRecord() { /* stderr and error text contain the token; run row doesn't */ }
@Test void dryRunWritesNothingButCounts() { /* no items, cursor unchanged, no model calls, no deletes; itemsSeen and chars set */ }
@Test void dryRunStillSavesARotatedSecret() { }
@Test void cancellationEndsTheRunAndTheProcess() { }
@Test void rawModeRunsWithoutTheModel() { }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :daemon:test --tests '*HarvestRunServiceTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement the run service**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :daemon:test --tests '*HarvestRunServiceTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src
git commit -m "Run harvesters and apply their records"
```

---

### Task 6: Scheduler and restart recovery

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/harvest/HarvestScheduler.java`, `HarvestSchedule.java`
- Modify: `modules/daemon/.../reminiscence/ReminiscenceService.java` (`adoptSessionOrphans`)
- Modify: `modules/daemon/src/main/resources/application.properties` (`pieria.harvest.*` defaults)
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/harvest/HarvestScheduleTests.java`, `HarvestSchedulerTests.java`

**Interfaces:**
- Consumes: `HarvestRunService.run`, `HarvestRunSummary`, `HarvestStore`, `HarvestProperties`.
- Produces:
  - `final class HarvestSchedule`: `static Instant nextRunAt(Instant now, long intervalSeconds, int consecutiveFailures, Long retryAfterSeconds, Duration maxBackoff, double jitter)`. Success (`consecutiveFailures == 0`) is `now + interval * (1 + 0.1 * jitter)`. Failure is `now + max(min(interval * 2^failures, maxBackoff), retryAfter)`, with no jitter.
  - `@Component class HarvestScheduler`: `@Scheduled void tick()`, `Optional<UUID> launch(String sourceId, boolean dryRun)` (empty when that source is already running or capacity is full), `@EventListener(ApplicationReadyEvent.class) void recover()`
  - `ReminiscenceResult ReminiscenceService.adoptSessionOrphans(String profileName, List<String> sessions, IngestProgressListener progress)`, phase label `harvest-graph`

**Behaviour:** `tick` does nothing when disabled, and launches due sources up to `maxConcurrentRuns`. Each run is a `TaskRegistry` task of kind `harvest`. After a run, non-dry runs call `scheduleNext` with `consecutiveFailures` reset on success and incremented on failure. A dry run leaves the schedule alone. A successful `FULL` run that stored memories submits a `harvest-graph` task with that run's sessions. A task-level exception must still release capacity and reschedule, so one bad source can't stop the others.

- [ ] **Step 1: Write the failing tests**

```java
// HarvestScheduleTests
@Test void successWaitsTheInterval() { /* jitter 0 */ }
@Test void jitterAddsUpToTenPercent() { /* jitter 1.0: 1.1 * interval */ }
@Test void failuresBackOffExponentially() { }
@Test void backoffIsCapped() { }
@Test void retryAfterWinsWhenLonger() { }
@Test void hugeFailureCountDoesNotOverflow() { /* failures = 500 returns now + maxBackoff */ }

// HarvestSchedulerTests: mock HarvestRunService and ReminiscenceService, real TaskRegistry and store
@Test void launchesDueSources() { }
@Test void disabledDoesNothing() { }
@Test void respectsTheConcurrencyCap() { /* 2 due, cap 1: one launched */ }
@Test void doesNotRunTheSameSourceTwice() { }
@Test void successResetsFailures() { }
@Test void failureIncrementsFailuresAndBacksOff() { }
@Test void aCrashingRunStillReleasesCapacityAndReschedules() { /* run throws RuntimeException: next tick can launch; source rescheduled */ }
@Test void dryRunLeavesTheScheduleAlone() { }
@Test void recoverMarksInterruptedRunsAndMakesThemDue() { }
@Test void resumesFromTheLastCheckpointAfterRecovery() { /* source with a saved cursor stays due with that cursor after recover() */ }
@Test void successfulRunSubmitsGraphAdoption() { }
@Test void rawOrEmptyRunSkipsGraphAdoption() { }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :daemon:test --tests '*HarvestScheduleTests' --tests '*HarvestSchedulerTests'`
Expected: FAIL at compile.

- [ ] **Step 3: Implement the scheduler**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :daemon:test --tests '*HarvestScheduleTests' --tests '*HarvestSchedulerTests'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src
git commit -m "Schedule harvest runs and recover after restarts"
```

---

### Task 7: Keep harvested text out of clusters and wikis, and update the documents

**Files:**
- Modify: `modules/daemon/.../cluster/SqliteClusterStore.java`, `modules/daemon/.../storage/SqliteWikiStore.java`
- Modify: `docs/phases/phase-16-knowledge-harvesters.md`, `AGENTS.md`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/harvest/HarvestedTextIsolationTests.java`

**Interfaces:**
- Consumes: `MemoryOrigins.withOrigin`.
- Produces: no new API. Harvested memories stop being cluster or wiki inputs until those features can carry the untrusted label.

Add `origin_source_id IS NULL` to these queries:
- `SqliteClusterStore`: the membership existence check (~line 80), the evidence query (~357), `topics` (key and member queries), `entityTopics` (entity and member queries), and the seed query containing `m.type NOT IN ('cluster','task')` (~852).
- `SqliteWikiStore`: `sources` and `loadSource`.

- [ ] **Step 1: Write the failing tests**

Real migrated store, with `ClusterRepository` through `store.readClusters` and `SqliteWikiStore` directly.

```java
@Test void clusterTopicsIgnoreHarvestedMemories() { /* 3 harvested memories on one key: no topic; 3 own: a topic */ }
@Test void clusterEntityTopicsIgnoreHarvestedMemories() { }
@Test void clusterSeedsIgnoreHarvestedMemories() { }
@Test void aHarvestedMemoryCannotBeAddedToACluster() { /* membership path throws IllegalArgumentException */ }
@Test void wikiSourcesIgnoreHarvestedMemories() { }
@Test void wikiSourceLookupIgnoresHarvestedMemories() { /* source(...) empty */ }
@Test void ownMemoriesStillReachClustersAndWikis() { }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :daemon:test --tests '*HarvestedTextIsolationTests'`
Expected: FAIL, as harvested memories are included.

- [ ] **Step 3: Add the predicates**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :daemon:test --tests '*HarvestedTextIsolationTests'`
Expected: PASS

- [ ] **Step 5: Update the documents**

Phase 16 doc: status to phases 1 to 3 complete. Close the open item, stating harvested memories are excluded from cluster and wiki inputs. Add amendments: harvesters and their pins and secrets live under `<app-data root>/harvesters`, because `$PIERIA_HOME` is replaced by updates; the mass-delete guard floor of 2; sweep-blocked sources keep running with deletes held back, and phase 4 adds the way to accept a blocked sweep; a run's graph adoption is scoped to that run's sessions.

`AGENTS.md`: add a design-constraint bullet on the run rules: cursors after durable processing, sweeps only after a clean exit, harvested memories excluded from clusters and wikis.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add modules/daemon/src AGENTS.md docs/phases/phase-16-knowledge-harvesters.md docs/superpowers/plans/2026-11-09-harvester-daemon-runtime.md
git commit -m "Keep harvested text out of clusters and wikis"
```
