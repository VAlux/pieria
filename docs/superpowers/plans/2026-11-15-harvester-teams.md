# Harvesters Phase 7: Microsoft Teams Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bundled `teams` harvester that reads the signed-in user's Teams chats and, when asked, named channels. Sign-in uses Microsoft's device code, run by the harvester and shown by `pieria source authorize <id>`.

**Architecture:**
- **Protocol v1 gains a third command, `authorize`, and one record, `device_code`.**
  - The harvester asks Microsoft for a code, reports it in a `device_code` record, waits for the user to sign in, then saves the refresh token with `secret_update`.
  - The daemon runs the command in the background and relays the code over REST. The CLI prints it and polls.
  - Only harvesters ever talk to outside services. Any third-party harvester can use the same flow.
- **Item shapes.** A chat becomes one `thread` per chat per day, `chat:<chatId>:<yyyy-MM-dd>`. A channel post and its replies become one `thread`, `channel:<teamId>:<channelId>:<postId>`.
- **The Teams feed runs on phase 6's `IncrementalHarvest` engine.** The engine gains one opt-in behaviour: a listed change that loads as nothing is reported as a `tombstone`. A day whose messages were all deleted, or a deleted channel post, is forgotten that way.
- **Teams never sweeps.** Listing every live item would mean reading every message ever sent.
- **HTTP plumbing is shared.** `AtlassianClient`'s transport (bounded body, inline `Retry-After`, server clock) moves into `http/JsonTransport`, which the Microsoft login and Graph clients share.

**Tech Stack:** Java 25, `java.net.http`, Jackson 3 (`tools.jackson`), jsoup 1.16.1, JUnit 5, AssertJ, picocli (CLI), Spring Boot 4 (daemon only).

**Spec:** `docs/phases/phase-16-knowledge-harvesters.md`. The relevant sections are:
- "First-batch harvesters", the MS Teams row and the Teams risk note;
- "Protocol v1";
- "Delivery phases", item 7;
- "Amendments since approval". These bind. In particular:
  - `check` may emit only `secret_update`;
  - exit codes are 0, 1 and 2;
  - `sweep` semantics;
  - checkpoints from a failed run stand;
  - the IncrementalHarvest rules from phase 6.

**Settled with the user for this phase:**
- **Sign-in runs in the harvester**, through a new `authorize` command and `device_code` record, relayed by the daemon to `pieria source authorize <id>`.
- **Chats and channels both ship.** Channels are opt-in: they are read only when the config names them, and only then does sign-in ask for the admin-consented `ChannelMessage.Read.All`.

**What the spike found (Graph v1.0, checked 2026-11-15):**

| Need | Endpoint | Delegated permission | Admin consent |
|---|---|---|---|
| Signed-in user | `GET /me` | `User.Read` | no |
| The user's chats | `GET /me/chats?$expand=lastMessagePreview` (`$top` ≤ 50, `@odata.nextLink`) | `Chat.Read` | no |
| A chat's messages | `GET /chats/{id}/messages` (`$top` ≤ 50) | `Chat.Read` | no |
| Chat members | `GET /chats/{id}/members` | `Chat.Read` | no |
| Joined teams | `GET /me/joinedTeams` | `Team.ReadBasic.All` | no |
| A team's channels | `GET /teams/{id}/channels` | `Channel.ReadBasic.All` | no |
| Changed channel posts | `GET /teams/{t}/channels/{c}/messages/delta?$filter=lastModifiedDateTime gt X&$expand=replies` | `ChannelMessage.Read.All` | **yes** |
| More replies | `GET /teams/{t}/channels/{c}/messages/{id}/replies` | `ChannelMessage.Read.All` | **yes** |

- **Chat message queries.** `GET /chats/{id}/messages` sorts only in **descending** order, by `lastModifiedDateTime` (the default) or by `createdDateTime`. `$filter` works only together with `$orderby` on the same property: `lastModifiedDateTime` supports `gt` and `lt`, while `createdDateTime` supports only `lt`.
- **Chat delta needs an application permission.** `/users/{id}/chats/getAllMessages/delta` is app-only, so a delegated harvester filters each chat by `lastModifiedDateTime` instead.
- **Channel delta reaches back eight months.** The filtered call returns every reply chain created or changed after the date. Expanded replies page with `replies@odata.nextLink` past 1,000.
- **Edits and deletions.** A soft-deleted message keeps appearing with `deletedDateTime` set and a new `lastModifiedDateTime`. System events have `messageType` other than `message`.
- **Device code flow.**
  - `POST {login}/{tenant}/oauth2/v2.0/devicecode` returns `device_code`, `user_code`, `verification_uri`, `expires_in` (900 by default), `interval` and `message`.
  - Polling `POST …/token` answers `authorization_pending`, `slow_down`, `authorization_declined`, `expired_token` or `bad_verification_code`.
  - A refresh token comes back only when `offline_access` was asked for.
  - The app registration must allow public client flows.

## Global Constraints

- **Process:**
  - `./gradlew test` must pass before every commit.
  - Never run `nativeCompile`, `nativeDist` or `deployLocal`.
  - Work on branch `harvesters-phase-7`, never on `main`.
- **Protocol:**
  - Harvesters never touch the store or call a model. Records go through `RecordSink` only.
  - `authorize` may emit at most one `device_code`, any number of `secret_update`, and on failure one `error`. Nothing else.
  - Protocol stays version 1: the addition is backward compatible, because a harvester that never declares `oauth-device-code` is never sent `authorize`.
- **Secrets:**
  - Never store or log secrets, credentials or tokens outside `HarvestSecretStore`.
  - Refresh and access tokens never appear in an `error` message, stderr, a log line or a REST response.
  - The device code is not a secret, since the user must read it, but the `device_code` grant value is, and it stays in the harvester process.
- **Network:**
  - Requests go only to `loginUrl` (default `https://login.microsoftonline.com`) and `graphUrl` (default `https://graph.microsoft.com`). Redirects are never followed.
  - A `@odata.nextLink` outside `graphUrl + "/v1.0/"` fails the run `transient`.
  - Both URLs must be `https`, except a loopback host (`localhost`, `127.0.0.1`, `[::1]`), which may be `http`.
  - User-Agent is the constant `PieriaHarvester/1.0`.
- **Code and tests:**
  - Test classes use the `*Tests` suffix.
  - No test seams in production code.
  - `Optional` is used only as a return type at a lookup boundary.
  - Controllers follow `.claude/rules/controller-service-layering.md`.
  - `:harvester-sdk` still depends on no other Pieria module.
  - Test HTTP goes to localhost stubs, so CI needs no network.
- **Native image:**
  - New code uses Jackson's tree model only, so no reflection hints are needed.
  - New REST DTOs are records in `modules/shared/.../api/response`, alongside `SourceCheckResponse`, and are registered wherever that one is. Check `DaemonNativeHints` for a list and add the new record next to it if one exists.

## Review Focus

1. **The user never finishes signing in, or closes the terminal while `pieria source authorize` waits.**
   - The daemon's session ends at the code's expiry plus 30 seconds, and the harvester process is killed.
   - A second `authorize` replaces the first and doesn't leak a process.
   - Removing the source, or shutting down the daemon, kills it.
   - Pinned in Task 2.
2. **A refresh token is revoked, expires, or was granted without the channel permission.**
   - `check` and `harvest` fail `auth`, with a message naming `pieria source authorize`, and the source goes to `needs_auth`.
   - Adding channels to the config of a chats-only sign-in fails `auth`, naming the missing permission, rather than `config` or `fatal`.
   - Pinned in Tasks 5 and 7.
3. **Every message of a day is deleted, or a channel post is deleted.** The next run emits a `tombstone` for that item. A day that only ever held system events emits a tombstone the daemon ignores, and the run doesn't count it as a deletion. Pinned in Tasks 2, 4 and 7.
4. **A message posted at 23:30 local time.**
   - It belongs to the day in `timeZone`, not the UTC day.
   - Editing it re-extracts only that day's window.
   - A message created before the `sinceDays` horizon never creates a window, even when it is edited later.
   - Pinned in Tasks 6 and 7.
5. **Microsoft rotates the refresh token on every use.** The new value is emitted with `secret_update` before any item, so a run that fails halfway still leaves a working token behind. Pinned in Task 7.

---

### Task 1: Protocol: the `authorize` command and `device_code` record

**Files:**
- Modify:
  - `modules/harvester-sdk/src/main/java/dev/alvo/pieria/harvester/protocol/HarvestRecord.java`
  - `…/protocol/RecordCodec.java`
  - `…/protocol/ProtocolLimits.java`
  - `…/Harvester.java`
  - `…/HarvesterRunner.java`
  - `modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/PieriaHarvest.java`, whose usage string becomes `check|harvest|authorize`
- Test:
  - `modules/harvester-sdk/src/test/java/dev/alvo/pieria/harvester/protocol/RecordCodecTests.java`
  - `…/HarvesterRunnerTests.java`

**Interfaces:**
- Produces:
  - `record HarvestRecord.DeviceCode(String verificationUri, String userCode, String message, int expiresInSeconds) implements HarvestRecord`
    - Wire type `device_code`, with fields `verificationUri`, `userCode`, `message` (omitted when null) and `expiresIn`.
    - Validation:
      - `verificationUri` is required, starts with `https://` and is at most `MAX_URL_CHARS`;
      - `userCode` is non-blank and at most `ProtocolLimits.MAX_USER_CODE_CHARS = 64`;
      - `message` is optional and at most `MAX_ERROR_CHARS`;
      - `expiresInSeconds` is in `1..ProtocolLimits.MAX_SIGN_IN_SECONDS` (`3600`).
  - `Harvester.authorize(HarvestRequest request, RecordSink sink) throws HarvesterException`, a `default` method that throws `HarvesterException(ErrorClass.CONFIG, "this harvester has no sign-in")`.
  - `HarvesterRunner.run` accepts `authorize`. The usage line becomes `usage: <harvester> check|harvest|authorize`. The sink given to `authorize` allows `DeviceCode` at most once, plus `SecretUpdate`.
    - Anything else throws `ProtocolViolation("authorize may only emit device_code and secret_update")`.
    - A second `DeviceCode` throws `ProtocolViolation("authorize may emit one device_code")`.

- [ ] **Step 1: Write the failing tests**

In `RecordCodecTests`:
```java
@Test
void deviceCodeRoundTrips() {
  var code = new HarvestRecord.DeviceCode("https://microsoft.com/devicelogin", "ABCD-EFGH", "Open the page and enter ABCD-EFGH", 900);
  String wire = RecordCodec.encode(code);
  assertThat(wire).contains("\"type\":\"device_code\"").contains("\"expiresIn\":900");
  assertThat(RecordCodec.decode(wire)).isEqualTo(code);
}

@Test
void deviceCodeRejectsPlainHttpBlankCodeAndLongExpiry() {
  assertThatThrownBy(() -> new HarvestRecord.DeviceCode("http://x", "A", null, 900)).isInstanceOf(ProtocolViolation.class);
  assertThatThrownBy(() -> new HarvestRecord.DeviceCode("https://x", " ", null, 900)).isInstanceOf(ProtocolViolation.class);
  assertThatThrownBy(() -> new HarvestRecord.DeviceCode("https://x", "A", null, 3601)).isInstanceOf(ProtocolViolation.class);
  assertThatThrownBy(() -> new HarvestRecord.DeviceCode("https://x", "A", null, 0)).isInstanceOf(ProtocolViolation.class);
}
```

In `HarvesterRunnerTests`, using its existing `run(harvester, command)` and `records()` helpers and a harvester whose `authorize` emits what the test gives it:
- `authorizeEmitsDeviceCodeThenSecret`: the harvester emits a `DeviceCode` and then `SecretUpdate("refreshToken", "r1")`. The exit code is `0`, and `records()` holds both, in order.
- `authorizeRefusesOtherRecords`: emitting a `Checkpoint` gives exit `1`, with a `fatal` `error` record whose message contains `authorize may only emit device_code and secret_update`.
- `authorizeRefusesASecondDeviceCode`: exit `1`, and the message contains `authorize may emit one device_code`.
- `harvesterWithoutSignInFailsConfig`: a harvester that doesn't override `authorize` gives exit `1` and a `config` error, `this harvester has no sign-in`.
- `unknownCommandStillPrintsUsage`: `run(harvester, "login")` returns `2`, and stderr contains `check|harvest|authorize`.

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew :harvester-sdk:test --tests '*RecordCodecTests' --tests '*HarvesterRunnerTests'`
Expected: compilation fails, because `HarvestRecord.DeviceCode` does not exist.

- [ ] **Step 3: Implement the record, the codec case, the limits, the `default authorize`, and the runner's third command with its sink filter**

`RecordCodec.decode` reads `expiresIn` with the existing `WireJson` integer helper. The record's `toString` may show every field, because none of them is secret.

- [ ] **Step 4: Run the SDK and harvesters tests and see them pass**

Run: `./gradlew :harvester-sdk:test :harvesters:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/plans/2026-11-15-harvester-teams.md modules/harvester-sdk modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/PieriaHarvest.java
git commit -m "Add the authorize command and device_code record to the harvester protocol"
```

---

### Task 2: Daemon: sign-in sessions

**Files:**
- Create:
  - `modules/daemon/src/main/java/dev/alvo/pieria/harvest/HarvestAuthorizationService.java`
  - `modules/daemon/src/main/java/dev/alvo/pieria/harvest/AuthorizationState.java`
  - `modules/daemon/src/main/java/dev/alvo/pieria/harvest/HarvestEnvelope.java`
- Modify:
  - `HarvestRunService.java`:
    - It uses `HarvestEnvelope` instead of its private `declaredSecrets`, `envelope`, `saveSecret` and `scrub`. Its constructor is unchanged.
    - A `tombstone` counts toward `itemsDeleted` only when the item was known.
  - `HarvestItemIngestor.delete`, which returns `boolean`: true when the item existed.
  - `HarvestSourceService.java`, which gains `authorize` and `authorization`. `remove` and `removeAllFor` cancel a pending sign-in.
- Test:
  - `modules/daemon/src/test/java/dev/alvo/pieria/harvest/HarvestAuthorizationServiceTests.java` (new)
  - `ScriptedHarvester.java`
  - `HarvestRunServiceTests.java`
  - `HarvestSourceServiceTests.java`

**Interfaces:**
- Consumes: Task 1's `HarvestRecord.DeviceCode`, and the `authorize` command.
- Produces:
  - `record AuthorizationState(Status status, String verificationUri, String userCode, String message, Instant expiresAt, ErrorClass errorClass, String error)`, with `enum Status { PENDING, AUTHORIZED, FAILED }`.
    - `status.wire()` gives `pending`, `authorized` or `failed`.
    - The code fields are null on a session that failed before a code arrived.
  - `final class HarvestEnvelope`, package-private and static-only:
    - `Set<String> declaredSecrets(RegisteredHarvester)`
    - `HarvestRequest build(HarvestSecretStore, HarvestSource, Set<String> declared, Set<String> secretValues) throws InvalidSource`, where `InvalidSource extends Exception` carries the existing messages
    - `void saveSecret(HarvestSecretStore, HarvestSource, Set<String> declared, Set<String> secretValues, HarvestRecord.SecretUpdate)`
    - `String scrub(String, Set<String>)`
  - `@Service HarvestAuthorizationService(HarvestStore store, HarvesterRegistry registry, HarvestSecretStore secretStore)`:
    - `AuthorizationState start(String sourceId)`:
      - It throws `IllegalArgumentException("unknown harvest source: " + id)`, or `IllegalArgumentException("harvester " + h + " has no sign-in; set its credentials with 'pieria source secret'")` when the manifest lacks `Capability.OAUTH_DEVICE_CODE`.
      - It cancels any earlier session for the source, launches `authorize`, and blocks until the first `device_code` (state `PENDING`), an `error`, the exit, or `CODE_TIMEOUT = 60s`.
    - `Optional<AuthorizationState> status(String sourceId)`
    - `void cancel(String sourceId)`
    - `@PreDestroy void shutdown()`
  - `HarvestSourceService` additions:
    - `AuthorizationState authorize(String profile, String id)`
    - `Optional<AuthorizationState> authorization(String profile, String id)`
    - Both check ownership with the existing `get(profile, id)`.

**Session rules, which the tests pin:**
- **After the code arrives**, a virtual thread reads the rest of the output until the exit, with a deadline of `expiresAt + 30s`.
  - Each `secret_update` is saved through `HarvestEnvelope.saveSecret`, and undeclared names are ignored.
  - An `error` record gives `FAILED`, with its class and scrubbed message.
  - An exit of `0` after at least one saved secret gives `AUTHORIZED`, and moves the source from `NEEDS_AUTH` to `ACTIVE`. `PAUSED` stays `PAUSED`.
  - An exit of `0` with nothing saved gives `FAILED`, `FATAL`, `the harvester finished without saving a sign-in`.
- **When the deadline passes**, the process is killed. The result is `FAILED`, `TRANSIENT`, `the sign-in timed out`.
- **A protocol violation**, such as a second `device_code` or any item, gives `FAILED`, `FATAL`, `protocol violation: …`.
- **Messages are scrubbed.** Every message is passed through `HarvestEnvelope.scrub` with the stored and newly saved secret values.
- **A finished state is kept** until the next `start` or `cancel`, so a poller can read it. `cancel` kills a running process and forgets the state.

- [ ] **Step 1: Teach `ScriptedHarvester` to authorize**

Add an `authorize` override that does these, in order:
1. Emits `config.authorizeRecords`, decoded with `RecordCodec`.
2. Sleeps `config.authorizeSleepMs` if it is set.
3. Emits `config.authorizeAfterRecords`.
4. Throws `config.authorizeFail` (`{class, message}`) if it is set.

Install it in the new test class with a manifest that adds `"oauth-device-code"` to `capabilities` and declares the secret `refreshToken`. Reuse `HarvestRunServiceTests.install()`'s script approach: copy the method, since a shared base class isn't worth it for two classes.

- [ ] **Step 2: Write the failing tests in `HarvestAuthorizationServiceTests`**

- `startReturnsThePendingCode`:
  - `authorizeRecords` holds `device_code` (`https://microsoft.com/devicelogin`, `ABCD`, `900`).
  - `authorizeSleepMs` is `500`.
  - `authorizeAfterRecords` holds `secret_update refreshToken=r1`.
  - `start("s")` returns `PENDING` with `userCode` `ABCD`, and `expiresAt` is about now plus 900 seconds, within 5 seconds.
  - Within 10 seconds, `status("s")` is `AUTHORIZED`, and `secretStore.secrets("s")` has `refreshToken` = `r1`.
- `authorizedSourceLeavesNeedsAuth`: with the source set to `NEEDS_AUTH` first, it ends `ACTIVE`. A `PAUSED` source stays `PAUSED`.
- `errorBeforeTheCodeFailsAtOnce`: `authorizeFail` = `{config, "clientId is wrong"}` with no records. `start` returns `FAILED`, `CONFIG`, `clientId is wrong`.
- `errorAfterTheCodeFails`: the code, then `authorizeFail` = `{auth, "declined"}` with a 200 ms sleep. The status ends `FAILED`, `AUTH`, `declined`.
- `exitWithoutASecretFails`: the code and nothing else. The status ends `FAILED`, with error `the harvester finished without saving a sign-in`.
- `expiredSessionIsKilled`:
  - The code has `expiresIn` `1` and `authorizeSleepMs` `120000`.
  - Within 40 seconds, the status is `FAILED`, `TRANSIENT`, `the sign-in timed out`, and the scripted process is gone.
  - The test writes a `pidFile`, as the existing scripted harvester does, and checks `ProcessHandle.of(pid)` is empty or not alive.
- `startAgainReplacesTheSession`:
  - `start` twice on a harvester that sleeps 120 s after the code.
  - The first process, from its `pidFile`, is dead.
  - `status` is the second session's `PENDING`.
- `cancelKillsAndForgets`: `cancel("s")` kills the process, and `status("s")` is empty.
- `harvesterWithoutTheCapabilityIsRefused`: a source on the plain `scripted` manifest gives `IllegalArgumentException`, with `has no sign-in`.
- `secondDeviceCodeIsAViolation`: `authorizeRecords` holds two codes. `start` returns `PENDING`, and the status ends `FAILED`, `FATAL`, with a message starting `protocol violation`.

In `HarvestRunServiceTests`:
- `tombstoneForAnUnknownItemIsNotCounted`: `withRecords(tombstone("never-seen"), checkpoint(1))` gives a summary with `itemsDeleted()` `0`.

In `HarvestSourceServiceTests`:
- `removeCancelsAPendingSignIn`.
- `authorizeChecksTheProfile`: another profile's source gives the same not-found exception `get` throws today.

- [ ] **Step 3: Run the tests and see them fail**

Run: `./gradlew :daemon:test --tests '*HarvestAuthorizationServiceTests' --tests '*HarvestRunServiceTests' --tests '*HarvestSourceServiceTests'`
Expected: compilation fails, because `HarvestAuthorizationService` does not exist.

- [ ] **Step 4: Implement `HarvestEnvelope`, `AuthorizationState`, `HarvestAuthorizationService`, the source-service methods, and the `delete` return value**

Implementation notes:
- **Sessions** live in a `ConcurrentHashMap<String, Session>`. A `Session` holds its `HarvesterProcess` and an `AtomicReference<AuthorizationState>`.
- **Launching** uses `HarvesterProcess.start(harvester.command(), "authorize", harvester.directory(), request)` after `registry.requirePinned(harvester)`, exactly as `HarvestRunService.check` does. An `IOException`, `HarvesterIntegrityException` or `InvalidSource` becomes `FAILED` with the class `check` uses for it.
- **The new constructor argument.** `HarvestSourceService`'s constructor takes `HarvestAuthorizationService`. Update `HarvestSourceServiceTests`' construction to match.

- [ ] **Step 5: Run the daemon tests and see them pass**

Run: `./gradlew :daemon:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/daemon
git commit -m "Run harvester sign-ins in the daemon and relay the device code"
```

---

### Task 3: REST, client and `pieria source authorize`

**Files:**
- Create:
  - `modules/shared/src/main/java/dev/alvo/pieria/api/response/SourceAuthorizationResponse.java`
  - `modules/cli/src/main/java/dev/alvo/pieria/cli/command/harvest/SourceAuthorizeCommand.java`
- Modify:
  - `modules/daemon/src/main/java/dev/alvo/pieria/api/controller/SourceController.java`
  - `modules/shared/src/main/java/dev/alvo/pieria/client/HarvestClient.java`
  - `modules/cli/src/main/java/dev/alvo/pieria/cli/command/harvest/SourceCommand.java`, which registers the subcommand
  - `SourceAddCommand.java`
- Test:
  - the existing source controller test class, or a new `SourceControllerAuthorizeTests`, if none covers `SourceController`
  - `modules/cli/src/test/java/dev/alvo/pieria/cli/command/harvest/SourceCommandTests.java`

**Interfaces:**
- Consumes: Task 2's `HarvestSourceService.authorize` and `authorization`, and `AuthorizationState`.
- Produces:
  - `record SourceAuthorizationResponse(String status, String verificationUri, String userCode, String message, Instant expiresAt, String errorClass, String error)`, where `status` is `pending`, `authorized` or `failed`.
  - `POST /v1/profiles/{name}/sources/{id}/authorize` returns 200 with the response. An unknown source gives 404, and a harvester without sign-in gives 400 through the global `IllegalArgumentException` mapping.
  - `GET /v1/profiles/{name}/sources/{id}/authorize` returns 200 with the response, or `NotFoundException("no sign-in is in progress for source " + id)` as 404.
  - `HarvestClient.authorize(String profile, String id)` and `HarvestClient.authorization(String profile, String id)`, both returning `SourceAuthorizationResponse`.
  - `pieria source authorize <id>`, with the shared profile option `AbstractSourceCommand` provides. It works like this:
    1. `POST`, then prints `message` when it is non-blank, else `Open <verificationUri> and enter the code <userCode>.`
    2. Prints `Waiting for you to sign in…` and polls `GET` every `POLL_INTERVAL = Duration.ofSeconds(1)` until the status isn't `pending`, or until `expiresAt + 60s` passes, which gives `Sign-in timed out.` and exit 1.
    3. `authorized` prints `Signed in.` and exits 0.
    4. `failed` logs `Sign-in failed (<errorClass>): <error>` and exits 1.
  - **`pieria source add` for a harvester whose `capabilities` contain `oauth-device-code`.** When no `refreshToken` secret was supplied, it skips the check and prints `Next: sign in with 'pieria source authorize <id>'.` Then it exits 0.

- [ ] **Step 1: Write the failing tests**

- **Controller:**
  - `authorizeReturnsThePendingCode`: a stubbed service returns `PENDING`, and the JSON has `"status":"pending"`, `"userCode":"ABCD"`.
  - `authorizationWithoutASessionIs404`.
- **CLI**, against a localhost stub daemon, as the existing tests do:
  - `authorizePrintsTheMessageAndWaits`: the stub answers `POST` with pending and message `To sign in, open https://microsoft.com/devicelogin and enter ABCD`. The first `GET` answers authorized. Output contains the message and `Signed in.`, and the exit code is `0`.
  - `authorizeFailureExitsOne`: the `GET` is failed (`auth`, `declined`). Output contains `Sign-in failed (auth): declined`, and the exit code is `1`.
  - `addSkipsTheCheckForASignInHarvester`: the stub harvester list has capabilities `["oauth-device-code"]`. No request reaches `/check`, and output contains `pieria source authorize`.

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew :daemon:test --tests '*SourceController*' :cli:test --tests '*SourceCommandTests'`
Expected: compilation fails, because `SourceAuthorizationResponse` does not exist.

- [ ] **Step 3: Implement the DTO, the two endpoints, the client methods, the command and the `source add` change**

The controller maps `AuthorizationState` to the DTO in a private helper and does nothing else.

- [ ] **Step 4: Run the shared, daemon and CLI tests and see them pass**

Run: `./gradlew :shared:test :daemon:test :cli:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/shared modules/daemon modules/cli
git commit -m "Add pieria source authorize and its REST endpoints"
```

---

### Task 4: Shared harvester pieces: transport, thread budget, gone items

**Files:**
- Create:
  - `modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/http/JsonTransport.java`
  - `modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/ThreadBudget.java`
- Modify:
  - `atlassian/AtlassianClient.java`, which keeps its URL building, auth header, `interpret` and `detail`, and delegates the sending, reading, retries, body cap and clock to `JsonTransport`. `encode` moves to `JsonTransport.encode`, and `AtlassianClient.encode` becomes a one-line delegate so the Jira and Confluence call sites stay unchanged.
  - `jira/IssueThreads.java`, which uses `ThreadBudget` and drops its private `Budget`.
  - `incremental/ChangeFeed.java` and `incremental/IncrementalHarvest.java`.
- Test:
  - `modules/harvesters/src/test/java/dev/alvo/pieria/harvesters/JsonStub.java`
  - `incremental/FakeFeed.java`
  - `incremental/IncrementalHarvestTests.java`
  - `http/JsonTransportTests.java` (new)

**Interfaces:**
- Produces:
  - `final class JsonTransport(String label, Duration requestDeadline)`:
    - `Response send(HttpRequest request) throws HarvesterException`, which retries `429` and `503` in process: up to `MAX_RETRIES = 3`, when `Retry-After` is at most `MAX_INLINE_WAIT = 30s`. A longer wait throws `RATE_LIMITED`, `<label> is limiting requests (HTTP n)`, with `retryAfter`. An `IOException` gives `TRANSIENT`, `could not reach <label>: <ExceptionClass>`. A body over `MAX_BODY_BYTES = 32 MiB` gives `TRANSIENT`.
    - `Instant serverTime()`
    - `static JsonNode json(byte[] body, String label) throws HarvesterException`, which gives `TRANSIENT`, `<label> answered with something that is not JSON`, for anything that isn't an object or array.
    - `static String encode(String)`
    - `record Response(int status, HttpHeaders headers, byte[] body)`
    - The label is what `AtlassianClient` used in its messages, `site.baseUrl()`, so every phase 6 message stays byte-identical.
  - `final class ThreadBudget` (public), with `String take(String text)` and `boolean spent()`, starting at `ProtocolLimits.MAX_TEXT_CHARS`.
  - `ChangeFeed.tombstonesGone()`, `default boolean`, returning `false`. When it returns true, `IncrementalHarvest` emits `HarvestRecord.Tombstone(change.id())` for a listed change that `load` left out, then `cursor.remember(change.id(), change.version(), change.changedAt())`. A tombstone doesn't count toward `maxItemsPerRun` or `maxCharsPerRun`.
  - `JsonStub.Request` gains `String method` and `String body`, the request body decoded as UTF-8 and empty for a `GET`. Existing call sites use only `path`, `query` and `header`.

- [ ] **Step 1: Write the failing tests**

In `IncrementalHarvestTests`, with `FakeFeed` gaining a `tombstonesGone` flag and a `vanish(String id)` that makes `load` leave the item out:
- `goneChangeBecomesATombstoneWhenTheFeedSaysSo`:
  - Two changes, `a` and `b`, with `b` vanished and the flag on.
  - The records are `a`, `progress`, `tombstone b`, then the final `checkpoint`, in that order.
  - A second run over the same listing emits neither again.
- `goneChangeIsSilentByDefault`: the same setup with the flag off gives no tombstone. This is the phase 6 behaviour.
- `tombstonesDoNotCountTowardTheItemLimit`: `maxItemsPerRun` `1`, and changes `x` (vanished), `y` and `z`. The run emits `tombstone x` and `y`, stops before `z`, and the next run emits `z`.

In `JsonTransportTests`, against a `JsonStub`:
- `postsTheFormBody`: a `POST` with `application/x-www-form-urlencoded` body `a=1&b=2` reaches the stub with `method` `POST` and `body` `a=1&b=2`.
- `retriesAShortRetryAfter`: one 429 with `Retry-After: 0`, then 200. The test gets the 200.

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew :harvesters:test --tests '*IncrementalHarvestTests' --tests '*JsonTransportTests'`
Expected: compilation fails, because `tombstonesGone` and `JsonTransport` don't exist.

- [ ] **Step 3: Implement the transport extraction, `ThreadBudget`, the engine hook and the stub fields**

- [ ] **Step 4: Run the whole harvesters suite and see it pass**

Run: `./gradlew :harvesters:test`
Expected: PASS. Every Atlassian, Jira and Confluence test passes unchanged, which shows the extraction kept their behaviour.

- [ ] **Step 5: Commit**

```bash
git add modules/harvesters
git commit -m "Share the JSON transport and thread budget, and let a feed report gone items"
```

---

### Task 5: Teams config, Microsoft sign-in and the Graph client

**Files:**
- Create, in `modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/teams/`:
  - `TeamsConfig.java`
  - `MicrosoftLogin.java`
  - `GraphClient.java`
  - `TeamsSession.java`
- Test, in `modules/harvesters/src/test/java/dev/alvo/pieria/harvesters/teams/`:
  - `StubMicrosoft.java`: one `JsonStub` serving both the login and Graph paths, so `loginUrl` and `graphUrl` are both its base URL
  - `TeamsConfigTests.java`
  - `MicrosoftLoginTests.java`
  - `GraphClientTests.java`

**Interfaces:**
- Consumes: Task 4's `JsonTransport`, and `ConfigValues` from phase 6.
- Produces:
  - **`record TeamsConfig(...)`.** Its components are `String tenant`, `String clientId`, `String loginUrl`, `String graphUrl`, `boolean includeChats`, `Set<String> chatTypes`, `List<ChannelRef> channels`, `int sinceDays`, `ZoneId timeZone` and `String refreshToken`.
    - `refreshToken` is nullable.
    - `loginUrl` and `graphUrl` are stored without a trailing slash.
    - `static TeamsConfig parse(HarvestRequest)`
    - `String scopes()`: space-separated. It starts with `offline_access User.Read`, adds ` Chat.Read` when `includeChats`, and adds ` Team.ReadBasic.All Channel.ReadBasic.All ChannelMessage.Read.All` when `channels` isn't empty.
    - `Set<String> graphScopes()`: the same list without `offline_access`.
  - **`record ChannelRef(String team, String channel)`.** `channel` is null for every channel in the team. It is parsed from `"Team"` or `"Team/Channel"`, split at the first `/`, with both parts stripped.
  - **Config fields and defaults:**
    - `tenant`: default `organizations`. Must match `[A-Za-z0-9.-]+`. `consumers` is refused: `personal Microsoft accounts can't read Teams through Graph`.
    - `clientId`: required, a GUID, or `clientId must be the application (client) ID, a GUID`.
    - `loginUrl` and `graphUrl`: defaults `https://login.microsoftonline.com` and `https://graph.microsoft.com`. Each must be an absolute URL with no path, query or fragment, and `https` unless the host is loopback: `<key> must be an https address`.
    - `includeChats`: default `true`.
    - `chatTypes`: a list, empty meaning all. Values must be in `oneOnOne`, `group` or `meeting`.
    - `channels`: a list, default empty. A blank team or channel part is refused.
    - `sinceDays`: `1..240`, default `90`.
    - `timeZone`: default `UTC`. Must be a valid `ZoneId`: `timeZone must be a zone such as Europe/Kyiv`.
    - `refreshToken`: a secret, optional.
    - With `includeChats` false and no channels: `nothing to harvest: turn on includeChats or name channels`.
  - **`final class MicrosoftLogin(TeamsConfig, JsonTransport)`:**
    - `DeviceCodeGrant start()`: `POST {loginUrl}/{tenant}/oauth2/v2.0/devicecode`, with form `client_id` and `scope`.
    - `Tokens await(DeviceCodeGrant)`: polls `POST …/oauth2/v2.0/token` with `grant_type=urn:ietf:params:oauth:grant-type:device_code`, `client_id` and `device_code`.
      - It sleeps `interval` seconds between polls; `interval` defaults to `5` when absent, and `0` is honoured.
      - `slow_down` adds 5 seconds to the interval.
      - It gives up at `expires_in`.
    - `Tokens refresh(String refreshToken)`: `grant_type=refresh_token`, `client_id`, `refresh_token`, `scope`.
    - `static void requireScopes(Tokens, TeamsConfig) throws HarvesterException`
  - **`record DeviceCodeGrant(String deviceCode, String userCode, String verificationUri, String message, int expiresIn, int interval)`.** Its `toString` leaves out `deviceCode`.
  - **`record Tokens(String accessToken, String refreshToken, Set<String> scopes)`.** Scopes are lower-cased, with any `https://graph.microsoft.com/` prefix removed. Its `toString` leaves out both tokens.
  - **Error mapping** for the token endpoint, which returns JSON `{error, error_description}` with HTTP 400:

    | Response | Result |
    |---|---|
    | `authorization_declined` | `AUTH`, `the sign-in was declined` |
    | `expired_token`, or the local timeout | `AUTH`, `the code expired before anyone signed in` |
    | `invalid_grant` or `interaction_required` on refresh | `AUTH`, `the sign-in has expired or was revoked; sign in again with 'pieria source authorize'` |
    | any other `error` | `AUTH`, `Microsoft refused the sign-in: <first line of error_description>` |
    | 5xx | `TRANSIENT` |
    | not JSON | `TRANSIENT` |
    | a token response without `refresh_token` | `AUTH`, `Microsoft returned no refresh token; the app needs the offline_access permission` |
  - **`requireScopes`** fails with `AUTH`, `Microsoft did not grant <scope>; sign in again with 'pieria source authorize'` for the first missing scope. When that scope is `ChannelMessage.Read.All`, the message adds ` (reading channels needs an admin to consent to ChannelMessage.Read.All)`. The check ignores case.
  - **`final class GraphClient(String graphUrl, String accessToken, JsonTransport)`:**
    - `JsonNode get(String pathAndQuery)`: the path starts with `/` and is resolved against `graphUrl + "/v1.0"`. A 404 throws `CONFIG`, `<path> was not found`.
    - `JsonNode getOrNull(String pathAndQuery)`: null on 404.
    - `JsonNode follow(String link)`: a `@odata.nextLink`. It must start with `graphUrl + "/v1.0/"`, else `TRANSIENT`, `graph returned a next link outside graphUrl`.
    - `Instant serverTime()`
    - Errors:

      | Status | Result |
      |---|---|
      | 401 | `AUTH`, `Microsoft Graph rejected the sign-in (HTTP 401)` |
      | 403 | `AUTH`, `Microsoft Graph denied access (HTTP 403): <error.message>` |
      | other 4xx | `CONFIG`, `Microsoft Graph refused <path without query> (HTTP n): <error.message>` |
      | 3xx and 5xx | `TRANSIENT` |
  - **`record TeamsSession(GraphClient graph, String userId, String userName)`**, with `static TeamsSession open(TeamsConfig, JsonTransport, RecordSink) throws HarvesterException`:
    1. A null `refreshToken` gives `AUTH`, `not signed in yet; run 'pieria source authorize <source id>'`.
    2. It refreshes, and emits `SecretUpdate("refreshToken", tokens.refreshToken())` when the token differs from the stored one.
    3. It runs `requireScopes`, then `GET /me` for `id` and `displayName`.

- [ ] **Step 1: Write the failing tests**

`StubMicrosoft` records requests, using `JsonStub`'s new `method` and `body`. Scripts set:
- the device-code reply;
- a queue of token replies, such as `pending`, `pending`, then tokens;
- the refresh reply;
- `/v1.0/me`;
- arbitrary Graph routes.

`TeamsConfigTests`:
- `defaults`: `tenant` `organizations`, `sinceDays` `90`, `timeZone` `UTC`, `includeChats` true, channels empty.
- `channelRefsSplitAtTheFirstSlash`: `"Eng / Ops/Night"` gives team `Eng`, channel `Ops/Night`.
- `scopesGrowWithChannels`:
  - Chats only gives `offline_access User.Read Chat.Read`.
  - With `["Eng"]`, it gives `offline_access User.Read Chat.Read Team.ReadBasic.All Channel.ReadBasic.All ChannelMessage.Read.All`.
- `rejects`, a `@ParameterizedTest` over (key, value, message fragment), each expecting `CONFIG`:
  - `clientId=abc`
  - `tenant=consumers`
  - `loginUrl=http://login.example.com`
  - `graphUrl=https://graph.microsoft.com/v1.0`
  - `chatTypes=["channel"]`
  - `sinceDays=241`
  - `timeZone=Mars/Base`
  - `channels=["/x"]`
  - `includeChats=false` with no channels
- `loopbackMayUseHttp`: `http://127.0.0.1:1234` is accepted for both URLs.

`MicrosoftLoginTests`:
- `deviceCodeIsRequestedWithClientAndScopes`: the body contains `client_id=<guid>` and `scope=offline_access+User.Read+Chat.Read`, or the `%20` form; the assertion decodes it first. The path is `/organizations/oauth2/v2.0/devicecode`.
- `awaitPollsUntilTokens`:
  - Two `authorization_pending` replies with `interval` `0`, then tokens.
  - The result has the refresh token, and three token requests carry `device_code=…`.
- `slowDownWidensTheInterval`: one `slow_down` reply; the next poll comes at least 5 seconds later. Mark the test `@Tag("slow")` only if the module already has such a tag; otherwise leave it, since 5 seconds is acceptable.
- `declinedAndExpiredAreAuth`: a parameterized test over `authorization_declined` and `expired_token`, with their messages.
- `refreshRevokedIsAuthNamingAuthorize`: `invalid_grant` gives `AUTH`, and the message contains `pieria source authorize`.
- `missingRefreshTokenIsAuth`.
- `requireScopesNamesTheAdminConsent`: tokens with scopes `User.Read Chat.Read` and a config with channels give `AUTH`, with `ChannelMessage.Read.All` and `admin`.
- `tokensStayOutOfToString`: `Tokens.toString()` contains neither token, and `DeviceCodeGrant.toString()` doesn't contain the device code. `HarvesterRunner` already scrubs secret values from `error` messages.

`GraphClientTests`:
- `bearerAndUserAgent`: the `Authorization: Bearer at1` and `User-Agent: PieriaHarvester/1.0` headers reach the stub.
- `followRefusesAForeignLink`.
- `followAcceptsALinkUnderGraphUrl`.
- `statusMapping`: a parameterized test over 401, 403 with an `error.message` body, 400, 404 through `get`, 404 through `getOrNull`, and 500.
- `serverTimeComesFromTheDateHeader`: reuse the raw `ServerSocket` approach from `AtlassianClientTests`. The JDK server overwrites `Date`; that ruling came from phase 6.

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew :harvesters:test --tests '*teams*'`
Expected: compilation fails, because the `teams` package does not exist.

- [ ] **Step 3: Implement `TeamsConfig`, `MicrosoftLogin`, `GraphClient` and `TeamsSession`**

Form bodies are built with `JsonTransport.encode`, and sent with `Content-Type: application/x-www-form-urlencoded` and `Accept: application/json`.

- [ ] **Step 4: Run the tests and see them pass**

Run: `./gradlew :harvesters:test --tests '*teams*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/harvesters
git commit -m "Add Teams config, Microsoft device-code sign-in and a Graph client"
```

---

### Task 6: Chat windows and channel threads

**Files:**
- Create, in `modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/teams/`:
  - `TeamsMessages.java`
  - `ChatWindows.java`
  - `ChannelThreads.java`
- Test: `ChatWindowsTests.java` and `ChannelThreadsTests.java`, in the matching test package

**Interfaces:**
- Consumes: Task 4's `ThreadBudget`, and `HtmlToMarkdown.convert(Jsoup.parseBodyFragment(html))`, the way `IssueThreads` uses them.
- Produces:
  - **`TeamsMessages`, static methods:**
    - `Instant created(JsonNode message)` and `Instant modified(JsonNode message)`, both parsed with `Instant.parse`.
    - `String author(JsonNode message)`: `from.user.displayName`, else `from.application.displayName`, else `unknown`, cut to `MAX_AUTHOR_CHARS`.
    - `String text(JsonNode message)`: `body.contentType` `html` is converted to Markdown, otherwise the content is used as-is. The result is stripped.
    - `boolean skipped(JsonNode message)`: true when `messageType` isn't `message`, `deletedDateTime` isn't null, or `text` is blank.
    - `boolean trivial(String text)`: lower-case it, replace every character that isn't a letter, digit or space with nothing, collapse spaces, strip. It is true when the result is empty or is one of `ok`, `okay`, `k`, `kk`, `thanks`, `thank you`, `thx`, `ty`, `np`, `yes`, `no`, `yep`, `nope`, `sure`, `lol`, `cool`, `great`, `nice`, `done` or `1`.
    - `List<HarvestRecord.Turn> turns(List<JsonNode> messages, boolean keepFirst)`:
      - It sorts by `created`, then by numeric id.
      - It drops `skipped` messages, and `trivial` ones, except the first message when `keepFirst` is set.
      - It keeps the oldest turns within `MAX_TURNS` and a `ThreadBudget`, and stops when the budget is spent.
  - **`ChatWindows`:**
    - `static String windowId(String chatId, LocalDate day)`, giving `"chat:" + chatId + ":" + day`.
    - `static LocalDate day(Instant created, ZoneId zone)`.
    - `static HarvestRecord.ConversationThread build(JsonNode chat, List<String> otherMembers, LocalDate day, List<JsonNode> messages)` returns null when no turn survives. Its fields:
      - **Title:** the chat's `topic` when non-blank; otherwise `Chat with ` + the first 5 of `otherMembers` joined by `, `, plus ` and N more` beyond 5; otherwise `Chat`. Then ` · ` + day, all cut to `MAX_TITLE_CHARS`.
      - **URL:** `chat.webUrl`, else `https://teams.microsoft.com/l/chat/` + URL-encoded `chat.id` + `/0`.
      - **Metadata:** `kind=chat`, `chatType`, `date`.
      - **Turns:** `turns(messages, false)`.
  - **`ChannelThreads`:**
    - `static String threadId(String teamId, String channelId, String postId)`, giving `"channel:" + teamId + ":" + channelId + ":" + postId`.
    - `static HarvestRecord.ConversationThread build(String teamName, String channelName, JsonNode post, List<JsonNode> replies)` returns null when the post is `skipped`. Its fields:
      - **Turns:** the post first (so `keepFirst` is true), then the replies, all through `turns`.
      - **Title:** `teamName + " / " + channelName + ": "` + the post's `subject` when non-blank, else the first line of its text cut to 80 characters.
      - **URL:** `post.webUrl`, else `https://teams.microsoft.com`.
      - **Metadata:** `kind=channel`, `team`, `channel`.

- [ ] **Step 1: Write the failing tests**

`ChatWindowsTests`:
- `lateEveningBelongsToTheLocalDay`: `2026-11-14T22:30:00Z` falls on `2026-11-15` in `Europe/Kyiv` and on `2026-11-14` in `UTC`.
- `buildsTurnsOldestFirst`: three html messages given newest first give turns in time order. Authors come from `from.user.displayName`. The text `<p>Deploy <b>now</b></p>` becomes `Deploy **now**`, or whatever `HtmlToMarkdown` makes of bold; assert it contains `Deploy` and `now`.
- `skipsSystemDeletedAndTrivial`:
  - The input is a `systemEventMessage`, a message with `deletedDateTime`, a `👍`, `Thanks!`, `ok`, and `Let's move the release to Friday`.
  - Only the last survives.
- `nothingLeftIsNull`: only trivial messages give `null`.
- `titleFromTopicOrMembers`:
  - Topic `Release` gives `Release · 2026-11-14`.
  - No topic, with members `[Ada, Bo]`, gives `Chat with Ada, Bo · 2026-11-14`.
  - Seven members give `Chat with A, B, C, D, E and 2 more · …`.
- `urlFallsBackToTheChatId`.
- `hugeDayKeepsTheOldestTurns`: 6,000 messages give `MAX_TURNS` turns, starting with the oldest.

`ChannelThreadsTests`:
- `postThenReplies`: the post's subject `Outage` gives the title `Eng / Ops: Outage`, with turns `[post, r1, r2]`.
- `trivialPostIsKeptTrivialReplyIsNot`: a post `ok` with a reply `thanks` gives one turn, `ok`.
- `deletedPostIsNull`.
- `titleFromFirstLineWithoutSubject`.

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew :harvesters:test --tests '*ChatWindowsTests' --tests '*ChannelThreadsTests'`
Expected: compilation fails.

- [ ] **Step 3: Implement `TeamsMessages`, `ChatWindows` and `ChannelThreads`**

- [ ] **Step 4: Run the tests and see them pass**

Run: same as Step 2.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/harvesters
git commit -m "Turn Teams chats into daily threads and channel posts into threads"
```

---

### Task 7: The Teams feed, harvester, manifest and bundling

**Files:**
- Create:
  - `modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/teams/TeamsFeed.java`
  - `…/teams/TeamsHarvester.java`
  - `modules/harvesters/manifests/teams/harvester.toml`
- Modify:
  - `PieriaHarvest.java`, which adds `"teams", TeamsHarvester::new`
  - `modules/daemon/src/main/java/dev/alvo/pieria/harvest/BundledHarvesters.java`, where `IDS` becomes `List.of("html", "jira", "confluence", "teams")`
  - `modules/daemon/src/test/java/dev/alvo/pieria/harvest/BundledHarvestersTests.java`: `teams` is bundled now. Pick an id that is still unbundled, such as `slack`, for the negative assertion.
- Test, in `modules/harvesters/src/test/java/dev/alvo/pieria/harvesters/teams/`:
  - `StubMicrosoft.java`, extended with chats, messages, members, teams, channels, delta and replies, each filtered the way Graph filters
  - `TeamsFeedTests.java`
  - `TeamsHarvesterTests.java`
  - `TeamsConformanceTests.java`

**Interfaces:**
- Consumes:
  - Task 5's `TeamsConfig`, `MicrosoftLogin`, `TeamsSession` and `GraphClient`;
  - Task 6's builders;
  - Task 4's `tombstonesGone`;
  - Task 1's `DeviceCode` and `authorize`.
- Produces:
  - `final class TeamsFeed implements ChangeFeed<JsonNode>`, constructed as `TeamsFeed(TeamsConfig, JsonTransport, RecordSink)`. Its constants are `QUIET_CHAT = Duration.ofDays(7)` and `PAGE = 50`.
  - `final class TeamsHarvester implements Harvester`:
    - **`check`** opens the feed (`begin()`), which signs in, reads `/me` and resolves the channels, and emits nothing else.
    - **`harvest`** runs `IncrementalHarvest.run(feed, request, sink, Duration.ofHours(24), "teams")`.
    - **`authorize`**:
      1. Parses the config; the refresh token may be absent.
      2. `start()`, then emits `DeviceCode(verificationUri, userCode, message, min(expiresIn, 3600))`.
      3. `await`, then `requireScopes`.
      4. Emits `SecretUpdate("refreshToken", tokens.refreshToken())`.

**Feed behaviour, which the tests pin:**
- **`begin()`.**
  1. `TeamsSession.open`. The secret update happens here, before any item.
  2. Each `ChannelRef` is resolved:
     - by `/me/joinedTeams`, matching `displayName` case-insensitively, following next links; otherwise `CONFIG`, `you are not a member of a team named <team>`;
     - then by `/teams/{id}/channels`; otherwise `CONFIG`, `team <team> has no channel named <channel>`.
     - A team-only ref takes every channel.
  3. Returns `graph.serverTime()`. The horizon is that time minus `sinceDays`.
- **`changes(from, token)`.** The bound is `from` when it is non-null and after the horizon, else the horizon. Times in `$filter` are ISO-8601 UTC with milliseconds (`2026-11-14T10:00:00.000Z`).
  - **Token values:**
    - `null` means the first chats page when `includeChats`, else channel 0.
    - `chats:<nextLink>` means the next chats page.
    - `channel:<i>` means channel *i*'s first delta page.
    - `channel:<i>:<nextLink>` means a later page.
    - After the last chats page comes `channel:0` when there are channels, else `null`. After channel *i*'s `@odata.deltaLink`, or a page with no link, comes `channel:<i+1>`, or `null` after the last channel.
  - **A chats page** is `GET /me/chats?$expand=lastMessagePreview&$top=50`. For each chat whose `chatType` is in `chatTypes` (or in all three when `chatTypes` is empty):
    - It skips the chat when `lastMessagePreview` is missing, or its `createdDateTime` is before `bound - QUIET_CHAT`.
    - Otherwise it pages `GET /chats/{id}/messages?$top=50&$orderby=lastModifiedDateTime desc&$filter=lastModifiedDateTime gt <bound>`, which includes deleted and system messages.
    - Each message whose local day starts at or after the horizon's local day gives `Change(windowId, String.valueOf(modified.toEpochMilli()), modified, chat)`.
  - **A channel page** is `GET /teams/{t}/channels/{c}/messages/delta?$filter=lastModifiedDateTime gt <bound>&$expand=replies&$top=50`. For each post:
    - `changedAt` is the latest `lastModifiedDateTime` of the post and its expanded replies, and `version` is its epoch milliseconds.
    - `data` is an `ObjectNode` holding `post`, `teamId`, `teamName`, `channelId` and `channelName`.
- **`liveIds(token)`** returns `new Page<>(List.of(), null)`, so there is never a sweep.
- **`tombstonesGone()`** returns `true`.
- **`load(changes)`:**
  - **A chat window:**
    - It pages `GET /chats/{id}/messages?$top=50&$orderby=createdDateTime desc&$filter=createdDateTime lt <next day's start in timeZone, as UTC>`, keeps messages created at or after the day's start, and stops paging at the first older message.
    - When the chat has no `topic`, members come from `GET /chats/{id}/members`, cached per chat for the run. Their `displayName`s exclude `userId`.
    - A 404 on the chat means it is gone: the window is left out, and the engine tombstones it.
  - **A channel thread** takes its replies from `post.replies`. When `replies@odata.nextLink` is present, it re-reads every reply from `GET /teams/{t}/channels/{c}/messages/{postId}/replies?$top=50`, following next links.
  - **A builder that returns null** leaves the item out.

**Manifest `teams/harvester.toml`:**
- `id = "teams"`, `name = "Microsoft Teams"`, `version = "1"`, `protocol = 1`, `command = ["./pieria-harvest", "teams"]`
- `capabilities = ["incremental", "deletes", "oauth-device-code"]`
- `network = ["login.microsoftonline.com, or loginUrl", "graph.microsoft.com, or graphUrl"]`
- **Fields, in order:**
  - `clientId`: required. Hint: `The Application (client) ID of an Entra app registration with "Allow public client flows" turned on`.
  - `tenant`: default `organizations`.
  - `includeChats`: bool, default `true`.
  - `chatTypes`: a list. Hint: `oneOnOne, group, meeting; empty for all`.
  - `channels`: a list. Hint: `"Team" or "Team/Channel". Reading channels needs an admin to consent to ChannelMessage.Read.All`.
  - `sinceDays`: int, default `90`. Hint: `How far back the first run reaches, at most 240`.
  - `timeZone`: default `UTC`. Hint: `Chats are grouped into one item per day in this zone`.
  - `refreshToken`: secret, not required. Label: `Sign-in (set by 'pieria source authorize')`.
  - `loginUrl`: default `https://login.microsoftonline.com`. Hint: `Change only for a national cloud`.
  - `graphUrl`: default `https://graph.microsoft.com`, with the same hint.

- [ ] **Step 1: Write the failing tests**

The stub's clock is fixed at `T = 2026-11-14T12:00:00Z`, through its `Date` header, and `timeZone` is `UTC` unless a test says otherwise.

`TeamsFeedTests`:
- `firstRunReachesBackSinceDays`: `sinceDays` `3`. The messages filter carries `gt 2026-11-11T12:00:00.000Z`. A message created on 2026-11-10 and edited on 2026-11-13 gives no window.
- `oneWindowPerChatPerDay`: three messages in chat `c1`, two on 11-13 and one on 11-14, give changes `chat:c1:2026-11-13` and `chat:c1:2026-11-14`. The 11-13 window's version is the later of its two `lastModifiedDateTime`s.
- `quietChatIsNotQueried`: a chat whose `lastMessagePreview.createdDateTime` is 8 days before the bound gets no `/messages` request.
- `chatTypesFilter`: `chatTypes=["group"]` skips a `oneOnOne` chat.
- `loadReadsTheWholeDay`:
  - The window `chat:c1:2026-11-13` loads messages from 11-13 only, even when the stub has 11-12 and 11-14 messages too.
  - The request uses `$orderby=createdDateTime desc` and `createdDateTime lt 2026-11-14T00:00:00.000Z`.
- `localZoneShiftsTheDay`: `timeZone` `Europe/Kyiv` (UTC+2 in November). A message at `2026-11-13T22:30:00Z` lands in `chat:c1:2026-11-14`.
- `untitledChatIsNamedForTheOthers`: no topic, with members `[me, Ada]`, gives the title `Chat with Ada · 2026-11-13`.
- `channelsAreResolvedByName`: `channels=["eng/ops"]` against teams `[Eng]` and channels `[Ops, General]` sends delta requests for Ops only.
- `unknownTeamIsConfig`, and `unknownChannelIsConfig`.
- `newReplyChangesTheThreadVersion`: the same post, with a reply added between two listings, gives a newer version.
- `moreThanTheExpandedRepliesAreFetched`: a post with `replies@odata.nextLink` has all its replies read from `/replies`.
- `foreignNextLinkIsTransient`.

`TeamsHarvesterTests`, driven through `PieriaHarvest.run(new String[]{"teams", cmd}, …)` with a newline-terminated envelope:
- `checkRotatesTheRefreshToken`: refresh returns `r2` for stored `r1`. The `check` output is exactly one `secret_update` `refreshToken=r2`, with exit `0`.
- `checkWithoutSignInIsAuth`: no `refreshToken` gives exit `1`, `auth`, `pieria source authorize`.
- `channelsAddedAfterAChatsOnlySignInIsAuth`: the granted scopes lack `ChannelMessage.Read.All` and the config names channels. The result is `auth`, containing `admin`.
- `harvestEmitsTheRotatedTokenFirst`: the first record is the `secret_update`, before any `thread`.
- `deletedDayBecomesATombstone`:
  1. Run 1 reports `chat:c1:2026-11-13`.
  2. Both messages that day are soft-deleted, with `deletedDateTime` and a new `lastModifiedDateTime`.
  3. Run 2, from run 1's cursor, emits `tombstone chat:c1:2026-11-13`.
- `deletedPostBecomesATombstone`: the same, for a channel post.
- `harvestNeverSweeps`: no `sweep` record, even on a first run.
- `authorizeEmitsCodeThenSecret`: the stub's device code `ABCD`, then one `authorization_pending`, then tokens with refresh `r9`. The records are exactly `device_code` (`userCode` `ABCD`) and then `secret_update refreshToken=r9`, with exit `0`.
- `authorizeDeclinedIsAuth`.

`TeamsConformanceTests`, mirroring `JiraConformanceTests`: the kit passes against `StubMicrosoft`, with chats and one channel, for a valid config. The invalid config is `clientId=abc`.

- [ ] **Step 2: Run the tests and see them fail**

Run: `./gradlew :harvesters:test --tests '*teams*'`
Expected: compilation fails, because `TeamsFeed` does not exist.

- [ ] **Step 3: Implement `TeamsFeed`, `TeamsHarvester`, the manifest, the `PieriaHarvest` entry and the bundled id**

- [ ] **Step 4: Run the whole suite and see it pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/harvesters modules/daemon
git commit -m "Harvest Microsoft Teams chats and channels"
```

---

### Task 8: Docs

**Files:**
- Modify:
  - `docs/phases/phase-16-knowledge-harvesters.md`
  - `README.md`, its harvester section
  - `AGENTS.md`, only if its harvester bullets name the commands; check before editing

- [ ] **Step 1: Update the phase document**

Change the status line to "phases 1–7 complete". Add an amendment block, **"Microsoft Teams, settled in phase 7"**, with one bullet each for:
- **The protocol addition:**
  - `authorize`, and `device_code` with its limits;
  - which records `authorize` may emit;
  - that the daemon runs it only for `oauth-device-code` harvesters;
  - the REST pair;
  - `pieria source authorize`;
  - the session ending at expiry + 30 s;
  - a successful sign-in moving `needs_auth` to `active`.
- **The permissions table** from this plan's spike, including which permission needs admin consent.
- **Item shapes:**
  - chat windows per local day, in `timeZone`;
  - channel posts with their replies;
  - trivial-message dropping, and its word list.
- **Change detection:**
  - the per-chat `lastModifiedDateTime` filter, and why (chat delta is app-only);
  - the seven-day quiet-chat skip, and what it misses: edits in a chat silent for longer;
  - channel delta with `$expand=replies`;
  - `sinceDays` capped at 240 by delta's eight months.
- **Deletions:**
  - tombstones from items that load as nothing;
  - no sweep;
  - a chat the user left is forgotten only once it answers 404.
- **Modes.** Raw mode for chats is a recommendation, not a default, because mode belongs to the source. A user wanting both splits chats and channels into two sources.

- [ ] **Step 2: README**

Add a Teams example after Confluence:
1. **Register an app** in Entra:
   - Mobile and desktop platform, "Allow public client flows" turned on.
   - Delegated `User.Read`, `Chat.Read`, plus the three channel permissions when channels are wanted.
   - Copy the Application (client) ID.
2. `pieria source add teams --id teams-chats --set clientId=<id> --mode raw`
3. `pieria source authorize teams-chats`
4. `pieria source run teams-chats --dry-run`

Add one line on channels needing admin consent.

- [ ] **Step 3: Run the suite and commit**

Run: `./gradlew test`
Expected: PASS.

```bash
git add docs README.md AGENTS.md
git commit -m "Document the Teams harvester and the sign-in protocol"
```

---

## Manual verification (yours, after `./gradlew :daemon:deployLocal`)

1. `pieria harvester list` shows `teams` as bundled, with capability `oauth-device-code`.
2. **Register an app** in your tenant, as in the README.
3. **Add and sign in:**
   1. Run `pieria source add teams --id t --set clientId=<id> --mode raw`. It skips the check and suggests `authorize`.
   2. Run `pieria source authorize t`. The Microsoft message appears; sign in in the browser, and the CLI prints `Signed in.`
4. **Run it:**
   1. `pieria source check t` passes.
   2. Run `pieria source run t --dry-run`, then a real run.
   3. Recall shows a chat fact labelled `untrusted · teams · chat:…`.
5. **Edit and delete:**
   1. Post a message in a chat, then run again: only that day is extracted.
   2. Delete every message you sent in a test chat that day, then run again: the day is forgotten.
6. **Channels.** Add `--set channels='["<Team>/<Channel>"]'` with `pieria source update`.
   - Without admin consent, `check` fails `auth`, naming `ChannelMessage.Read.All`.
   - With consent, run `authorize` again, then the channel posts appear.
7. **Revocation.** Revoke the sign-in at myaccount.microsoft.com → Apps. The next run fails `auth`, the source shows `needs_auth`, and `authorize` restores it.
