# Harvesters Phase 6: Jira and Confluence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two bundled harvesters, `jira` and `confluence`, to `pieria-harvest`. Each works against Atlassian Cloud and Data Center, picks up changes incrementally, and runs a periodic id sweep so items deleted at the source are forgotten.

**Architecture:**
- Both harvesters run on one source-neutral engine, `IncrementalHarvest`, so phase 7 (Teams) can reuse it.
- Each one supplies a `ChangeFeed`. A feed lists recently changed items oldest first, lists every live id, and loads one item.
- The engine owns:
  - the cursor: a watermark, the versions reported recently, and when the last sweep ran;
  - deduplication of overlapping pages;
  - run limits, checkpoints, and the sweep pass.
- A shared `AtlassianClient` handles the base URL, authentication, error mapping, short in-process retries and the server's clock.
- Jira issues become `thread` records: the description, then the comments.
- Confluence pages and blog posts become `document` records, converted from storage format to Markdown.

**Tech Stack:** Java 25, `java.net.http`, Jackson 3 (`tools.jackson`), jsoup 1.16.1 (its HTML and XML parsers), JUnit 5, AssertJ. No Spring in `:harvesters`.

**Spec:** `docs/phases/phase-16-knowledge-harvesters.md`. The relevant sections are:
- "First-batch harvesters", the Jira and Confluence rows;
- "Delivery phases", item 6;
- "Verification";
- "Amendments since approval". Its protocol rules are binding: `sweep` semantics, limits and exit codes.

## Global Constraints

- **Process:**
  - `./gradlew test` must pass before every commit.
  - Never run `nativeCompile`, `nativeDist` or `deployLocal`.
  - Work on branch `harvesters-phase-6`, never on `main`.
- **Protocol:**
  - Harvesters never touch the store or call a model. Records go through `RecordSink` only.
  - A run that hasn't listed every live id in that same run must not emit `sweep`. `sweep` is the last record apart from `progress`, so the sweep's checkpoint comes before it.
- **Deployments and auth:**
  - Cloud and Data Center are both supported.
  - Cloud authenticates with Basic `email:apiToken`.
  - Data Center uses Bearer with a personal access token, or Basic `username:password` when `email` holds a username.
- **Network:**
  - Every request goes to the configured `baseUrl`'s scheme, host and port, never anywhere else. Redirects are not followed, so credentials can't be sent to another host.
  - Private addresses are allowed, because Data Center usually runs on an intranet. Unlike the crawler, these harvesters never follow links taken from content.
  - User-Agent is the constant `PieriaHarvester/1.0`.
- **Code and tests:**
  - Test classes use the `*Tests` suffix.
  - No test seams in production code.
  - `Optional` is used only as a return type at a lookup boundary.
  - `:harvester-sdk` still depends on no other Pieria module.
  - Test HTTP goes to localhost stubs, so CI needs no network.
  - Don't log secret values. The SDK already strips them from `error` messages.

## Review Focus

1. **An issue edited while a Data Center search is being paged** moves to the end of the `updated` order, and with offset paging the items behind it shift left by one. No live issue may be skipped in that run's changes, and none may be left out of the sweep's `seen`. Pinned in Task 2 (overlap dedup) and Task 4 (`JiraFeed` asks for offset `startAt + size - 10`).
2. **The token has been revoked, or the JQL/CQL now matches nothing.** The run fails `auth`, or reports nothing and emits no `sweep`, so nothing is deleted. Pinned in Tasks 2, 3 and 4.
3. **`maxItemsPerRun` splits a backfill across runs.** The resumed run reports every item exactly once. More items than the limit share one `updated` minute, and the run must not livelock. Pinned in Task 2.
4. **A Cloud `nextPageToken` returns an earlier page.** This is a reported Atlassian bug. The run fails `transient` instead of looping forever. Pinned in Task 2.
5. **A ticket with thousands of comments or a huge description.** The thread stays within `MAX_TURNS` and `MAX_TEXT_CHARS`, and a new comment changes only the end of the thread, so the daemon extracts only that new comment. Pinned in Task 4.

---

### Task 1: Branch, plan, and shared config parsing

**Files:**
- Create: `modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/ConfigValues.java`
- Modify: `modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/html/CrawlConfig.java`, which drops its private `strings`, `integer` and `flag` helpers and calls `ConfigValues`.
- Test: `modules/harvesters/src/test/java/dev/alvo/pieria/harvesters/ConfigValuesTests.java`

**Interfaces:**
- Produces: `final class ConfigValues` with static methods. Every one throws `HarvesterException(CONFIG, …)` on a bad value and treats a missing or `null` node as absent:
  - `List<String> strings(ObjectNode config, String key)`: absent gives an empty list. Rejects a non-array, or an element that isn't a string.
  - `String string(ObjectNode config, String key)`: absent or blank gives `null`. Otherwise the value is stripped. Rejects a non-string.
  - `String requiredString(ObjectNode config, String key)`: like `string`, but `null` becomes `"<key> is required"`.
  - `int integer(ObjectNode config, String key, int fallback, int min, int max)`: the message `"<key> must be a whole number from <min> to <max>"` is unchanged from `CrawlConfig`.
  - `boolean flag(ObjectNode config, String key)`: absent gives `false`.
  - `String choice(ObjectNode config, String key, String fallback, List<String> options)`: compares lowercased. A value outside `options` gives `"<key> must be one of a, b"`.
  - `String requiredSecret(Map<String, String> secrets, String key)`: a missing or blank value gives `"the <key> secret is required"`.

- [ ] **Step 1:** `git switch -c harvesters-phase-6`, then commit this plan: `git add docs/superpowers/plans/2026-11-14-harvester-jira-confluence.md && git commit -m "Plan harvesters phase 6: Jira and Confluence"`.
- [ ] **Step 2: Write `ConfigValuesTests`.** Cover:
  - `stringsRejectsANonList`
  - `stringRejectsANumber`
  - `blankStringIsAbsent`
  - `requiredStringNamesTheKey`, whose message is `"jql is required"`
  - `integerOutsideRangeIsRejected`
  - `choiceIsCaseInsensitiveAndListsOptions`: `"Cloud"` gives `"cloud"`, and `"x"` gives a message containing `"cloud, datacenter, auto"`
  - `missingSecretIsRejected`

  Every rejection asserts `ErrorClass.CONFIG`.
- [ ] **Step 3:** Run `./gradlew :harvesters:test --tests '*ConfigValuesTests'`. Expected: compilation FAIL, because `ConfigValues` doesn't exist yet.
- [ ] **Step 4:** Implement `ConfigValues`, and switch `CrawlConfig` to it with no change in behaviour.
- [ ] **Step 5:** Run `./gradlew :harvesters:test`. Expected: PASS. The existing `CrawlConfigTests` still pass unchanged.
- [ ] **Step 6:** Commit with the message `Share harvester config parsing`.

---

### Task 2: The incremental engine

**Files:**
- Create the following in `modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/incremental/`: `Change.java`, `Page.java`, `ChangeFeed.java`, `ChangeCursor.java`, `IncrementalHarvest.java`
- Test: `.../src/test/java/dev/alvo/pieria/harvesters/incremental/ChangeCursorTests.java`, `IncrementalHarvestTests.java`, and `FakeFeed.java`, an in-memory `ChangeFeed` test double.

**Interfaces:**
- Produces:
  - `record Change<T>(String id, String version, Instant changedAt, T data)`. `version` identifies one state of the item: for Jira the `updated` epoch millis, for Confluence the version number.
  - `record Page<T>(List<T> entries, String next)`. A `null` `next` marks the last page. The constructor copies `entries`.
  - `interface ChangeFeed<T>` has four methods, all throwing `HarvesterException`:
    - `Instant begin()`: checks the credentials and returns the source's current time.
    - `Page<Change<T>> changes(Duration lookback, String token)`: items changed within `lookback` of the source's now, oldest change first. A `null` `lookback` means every item; a `null` `token` asks for the first page.
    - `Page<String> liveIds(String token)`: one page of the ids of every live item, in an order that edits don't change.
    - `HarvestRecord.Item load(Change<T> change)`: the record to report, or `null` when the item has gone since it was listed.
  - `final class ChangeCursor`:
    - `static ChangeCursor read(JsonNode cursor)`: `null`, the wrong `v`, or a malformed node gives a fresh cursor, never an exception.
    - `JsonNode toJson()`. The wire form is `{"v":1,"since":ISO|null,"windowStart":ISO|null,"lastSweep":ISO|null,"recent":{"<id>":["<version>",<changedAtMillis>]}}`.
    - Accessors and mutators for `since`, `windowStart` and `lastSweep`.
    - `boolean reported(String id, String version)`.
    - `void remember(String id, String version, Instant changedAt)`.
    - `void prune(Instant before)`: drops `recent` entries changed before `before`.
    - `MAX_RECENT = 10_000`: when it's exceeded, the entries with the oldest `changedAt` go first.
  - `final class IncrementalHarvest`:
    - `static <T> void run(ChangeFeed<T> feed, HarvestRequest request, RecordSink sink, Duration sweepEvery, String label)`
    - Constants `OVERLAP = Duration.ofMinutes(10)` and `CHECKPOINT_EVERY = 25`.

**The run algorithm.** The signatures don't determine it, so it's spelled out here.

```
cursor = ChangeCursor.read(request.cursor())
now = feed.begin()
if cursor.windowStart == null: cursor.windowStart = now            // a new window; a resumed one keeps its start
lookback = cursor.since == null ? null : between(cursor.since, now) + OVERLAP   // never negative
on any HarvesterException after begin(): emit checkpoint, then rethrow     // what was reported stands
page through feed.changes(lookback, token), remembering every token used; a repeated token →
    HarvesterException(TRANSIENT, label + " returned a page it had already sent")
  for each change:
    if cursor.reported(id, version): continue                       // overlap, resume, or a repeated row
    if maxItemsPerRun reached: checkpoint; return                    // no sweep
    item = feed.load(change); if item == null: continue
    chars = text length (a Document's text, or the sum of a thread's turn texts)
    if maxCharsPerRun set and emitted > 0 and total + chars > maxCharsPerRun: checkpoint; return
    emit item; emitted++; total += chars
    cursor.remember(id, version, changedAt)
    cursor.since = max(cursor.since, changedAt); cursor.prune(cursor.since - OVERLAP)
    emit Progress(emitted, null); every CHECKPOINT_EVERY items emit checkpoint
// the window is complete
cursor.since = cursor.windowStart; cursor.windowStart = null; cursor.prune(cursor.since - OVERLAP)
if cursor.lastSweep == null or between(lastSweep, now) >= sweepEvery:
  ids = ordered set; page through feed.liveIds (same repeated-token rule), adding each id
  if ids is empty: checkpoint; return                                // nothing to say; never "delete all"
  emit Seen in batches of ProtocolLimits.MAX_SEEN_IDS
  cursor.lastSweep = now; emit checkpoint; emit Sweep
else:
  emit checkpoint
```

- [ ] **Step 1: Write `ChangeCursorTests`.** Cover:
  - `roundTripsEveryField`
  - `unreadableCursorStartsFresh`: inputs `null`, `{"v":2}`, and `{"v":1,"recent":5}`
  - `pruneDropsOnlyOlderEntries`
  - `oldestEntriesGoBeyondTheCap`: `MAX_RECENT + 1` entries leave out the oldest
  - `reportedMatchesOnlyTheSameVersion`
- [ ] **Step 2: Write `FakeFeed` and `IncrementalHarvestTests`.**

  **`FakeFeed` fields:**
  - an item list of `id`, `version` and `changedAt`;
  - a fixed `now`;
  - a page size;
  - the last `lookback` seen;
  - a way to fail `begin()` with a given `HarvesterException`;
  - a way to make a given change page return `next` = an earlier token.

  `load` builds a `Document` whose text is `"text " + id`.

  **Tests**, collecting records with a list-backed `RecordSink`:
  - `firstRunReportsEverythingThenSweeps`: the records are items, then `Seen` with all ids, then `Checkpoint`, then `Sweep`, which is last. The first `lookback` is `null`.
  - `secondRunAsksOnlyForTheWindowPlusOverlap`: the second run's lookback equals `now2 - now1 + 10m`.
  - `unchangedItemsInTheOverlapAreNotReportedAgain`
  - `anEditedItemIsReportedAgain`
  - `sweepWaitsForItsInterval`: a run within `sweepEvery` of the last sweep emits no `Seen` and no `Sweep`.
  - `itemLimitStopsWithoutSweepAndResumeLosesNothing`: `maxItemsPerRun=1` over 3 items. Loop until a run emits `Sweep`. Every id is reported exactly once, and the final run's `Seen` covers all three.
  - `manyItemsInOneMinuteDoNotLivelock`: 5 items with the same `changedAt` and `maxItemsPerRun=2`. The sweep arrives within 3 runs.
  - `charLimitLetsTheFirstItemThrough`
  - `duplicateRowsFromOverlappingPagesAreReportedOnce`: the feed returns pages that overlap by 2.
  - `repeatedPageTokenFailsTransient`
  - `authFailureEmitsNothing`: `begin` throws `AUTH`, and no records are emitted.
  - `failureMidRunCheckpointsFirst`: `load` throws `RATE_LIMITED` on the second item. The records are item 1 then `Checkpoint`, and the exception propagates.
  - `emptySourceNeverSweeps`: no items and no live ids. The records end with `Checkpoint` and contain no `Sweep`.
  - `vanishedItemIsSkipped`: `load` returns `null`, and the run still completes and sweeps.
- [ ] **Step 3:** Run `./gradlew :harvesters:test --tests '*incremental*'`. Expected: compilation FAIL.
- [ ] **Step 4:** Implement the five types.
- [ ] **Step 5:** Run `./gradlew :harvesters:test`. Expected: PASS.
- [ ] **Step 6:** Commit with the message `Add an incremental change-feed engine for harvesters`.

---

### Task 3: The Atlassian site and client

**Files:**
- Create the following in `modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/atlassian/`: `Deployment.java`, `AtlassianSite.java`, `AtlassianClient.java`
- Create: `.../src/test/java/dev/alvo/pieria/harvesters/JsonStub.java`, a localhost `HttpServer` test helper shared by Tasks 3 to 5
- Test: `.../atlassian/AtlassianSiteTests.java`, `AtlassianClientTests.java`

**Interfaces:**
- Produces:
  - `enum Deployment { CLOUD, DATA_CENTER }`
  - `record AtlassianSite(String baseUrl, Deployment deployment, String authorization)`
    - `static AtlassianSite parse(ObjectNode config, Map<String,String> secrets, String cloudContextPath)`. Jira passes `""` and Confluence passes `"/wiki"`.
    - It reads these config keys:

      | Key | Kind | Rules |
      |---|---|---|
      | `baseUrl` | string, required | `http` or `https`, with no query or fragment. Trailing slashes are stripped. |
      | `deployment` | choice of `auto`, `cloud`, `datacenter` | Default `auto`, which means cloud when the host ends with `.atlassian.net`. |
      | `email` | string | Required for cloud, with the message `"email is required for Atlassian Cloud"`. |
      | `token` | secret, required | |

    - The authorization header is:
      - for cloud, `Basic base64(email:token)`;
      - for Data Center with an email (a username), `Basic base64(email:token)`;
      - for Data Center without one, `Bearer token`.
    - For cloud, when `baseUrl` has an empty path, `cloudContextPath` is appended.
    - `toString` never includes `authorization`.
  - `final class AtlassianClient`
    - Constructor `AtlassianClient(AtlassianSite site, Duration requestDeadline)`.
    - `JsonNode get(String pathAndQuery)`. The path starts with `/` and is resolved against `site.baseUrl()`.
    - `JsonNode getOrNull(String pathAndQuery)`: a 404 gives `null`.
    - `Instant serverTime()`: the `Date` header of the latest response, or `Instant.now()` before one arrives or when it can't be parsed.
    - `static String encode(String value)`: URL query encoding, UTF-8, with spaces as `%20`.
    - Constants `MAX_BODY_BYTES = 32 MiB`, `MAX_RETRIES = 3`, `MAX_INLINE_WAIT = Duration.ofSeconds(30)`.
- `get` sends `Accept: application/json`, the site's `Authorization` and the User-Agent, and never follows redirects. It maps responses as follows:

  | Response | Result |
  |---|---|
  | 2xx | parsed JSON. A body that isn't JSON gives `TRANSIENT`. |
  | 401 or 403 | `AUTH`, with the message `"<baseUrl> rejected the credentials (HTTP <code>)"` |
  | 429 or 503 | Retry-After seconds, defaulting to 5. When the wait is at most `MAX_INLINE_WAIT` and fewer than `MAX_RETRIES` retries have run, sleep and retry. Otherwise `RATE_LIMITED`, carrying that `retryAfter`. |
  | 3xx | `CONFIG`, with the message `"<baseUrl> redirects to <Location>; use that address as baseUrl"` |
  | 404 from `get` | `CONFIG` |
  | other 4xx | `CONFIG`, with the message built from the body's `errorMessages[]` and `errors{}` (Jira), or `message` (Confluence), else `"HTTP <code>"` |
  | 5xx, I/O failure, timeout, or a body over `MAX_BODY_BYTES` | `TRANSIENT` |

- [ ] **Step 1: Write `JsonStub`.**
  - `start()`, `baseUrl()`, `close()`.
  - `route(String path, Function<Request, Reply> handler)`, which matches the path without its query.
  - `record Request(String path, Map<String,String> query, Map<String,String> headers)`, with the query decoded and header names lowercased.
  - `record Reply(int status, Map<String,String> headers, String body)`, plus `static Reply json(Object value)`, serialized with Jackson.
  - `requests(String path)` lists the requests received, so tests can assert on them.
  - An unrouted path answers 404.
- [ ] **Step 2: Write `AtlassianSiteTests`.** Cover:
  - `autoDetectsCloudFromTheHost`
  - `cloudConfluenceGetsTheWikiPath`
  - `cloudNeedsAnEmail`
  - `dataCenterWithoutEmailUsesBearer`
  - `dataCenterWithUsernameUsesBasic`
  - `rejectsQueryInBaseUrl`
  - `stripsTrailingSlash`
  - `toStringHidesTheCredentials`
  - `missingTokenIsRejected`
- [ ] **Step 3: Write `AtlassianClientTests`** against `JsonStub`. Cover:
  - `sendsAuthorizationAndUserAgent`
  - `unauthorizedIsAuth` (401) and `forbiddenIsAuth` (403)
  - `shortRetryAfterIsRetriedInProcess`: 429 with `Retry-After: 1` once, then 200. The call succeeds, and the stub saw two requests.
  - `longRetryAfterIsRateLimited`: `Retry-After: 120` gives `RATE_LIMITED` with `retryAfterSeconds() == 120`.
  - `redirectIsNotFollowed`: 302 gives `CONFIG` mentioning the location, and the redirect target is never requested.
  - `badRequestCarriesJiraErrorMessages`
  - `serverErrorIsTransient`
  - `notFoundIsNullFromGetOrNull`
  - `serverTimeComesFromTheDateHeader`
  - `nonJsonBodyIsTransient`
- [ ] **Step 4:** Run `./gradlew :harvesters:test --tests '*atlassian*'`. Expected: compilation FAIL.
- [ ] **Step 5:** Implement the three types. Use `HttpClient.newBuilder().followRedirects(NEVER).connectTimeout(30s)` and read the body through `BodyHandlers.ofInputStream()` with a byte cap.
- [ ] **Step 6:** Run `./gradlew :harvesters:test`. Expected: PASS.
- [ ] **Step 7:** Commit with the message `Add an Atlassian client for the Jira and Confluence harvesters`.

---

### Task 4: The Jira harvester

**Files:**
- Create the following in `modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/jira/`: `JiraConfig.java`, `IssueThreads.java`, `JiraFeed.java`, `JiraHarvester.java`
- Create: `modules/harvesters/manifests/jira/harvester.toml`
- Modify:
  - `PieriaHarvest.java`: add `"jira", JiraHarvester::new` to `HARVESTERS`.
  - `modules/daemon/src/main/java/dev/alvo/pieria/harvest/BundledHarvesters.java`: `IDS` becomes `List.of("html", "jira")`. The existing `BundledHarvestersTests` requires `IDS` to match the manifest directories.
- Test: `.../src/test/java/dev/alvo/pieria/harvesters/jira/StubJira.java` (built on `JsonStub`), `IssueThreadsTests.java`, `JiraFeedTests.java`, `JiraHarvesterTests.java`, `JiraConformanceTests.java`

**Interfaces:**
- Consumes: `ConfigValues`, `AtlassianSite.parse(config, secrets, "")`, `AtlassianClient`, `ChangeFeed`/`Change`/`Page`, `IncrementalHarvest.run(..., label "jira")`, and `HtmlToMarkdown.convert(Document)`.
- Produces:
  - `record JiraConfig(AtlassianSite site, String jql, Duration sweepEvery)`
    - `static JiraConfig parse(HarvestRequest request)`
    - `jql` is required. When it contains `order by` in any case, the message is `"jql must not contain ORDER BY; the harvester orders results itself"`.
    - `sweepHours` is an integer, default 24, range 1 to 720.
  - `final class JiraFeed implements ChangeFeed<JsonNode>`, with constructor `JiraFeed(JiraConfig config, AtlassianClient client)`.
  - `final class IssueThreads`
    - `static HarvestRecord.ConversationThread build(String baseUrl, JsonNode issue, List<JsonNode> comments)`
    - Constant `FIELDS = "summary,description,status,issuetype,priority,assignee,reporter,creator,created,updated,labels,components,fixVersions,resolution"`.

**Endpoints**, where `{v}` is `3` on cloud and `2` on Data Center:

| Use | Cloud | Data Center |
|---|---|---|
| begin and check | `/rest/api/3/myself` | `/rest/api/2/myself` |
| changes | `/rest/api/3/search/jql?jql=…&fields=FIELDS&expand=renderedFields&maxResults=50[&nextPageToken=…]`. `next` = the response's `nextPageToken`, or `null` when it's missing or `isLast` is true. | `/rest/api/2/search?jql=…&fields=FIELDS&expand=renderedFields&startAt=N&maxResults=50`. `next` = `null` when `startAt + issues.size() >= total` or `issues.size() < maxResults`. Otherwise `next` = `startAt + issues.size() - 10`: a 10-row overlap that the engine deduplicates, absorbing rows that shifted while paging. |
| live ids | the same endpoint with `fields=created&maxResults=1000`, no `expand` | the same, with a 10-row overlap |
| comments | `/rest/api/{v}/issue/{id}/comment?startAt=N&maxResults=100&orderBy=created&expand=renderedBody`, paged until `startAt + size >= total`, through `getOrNull`. `null` means the issue is gone, and `load` returns `null`. | same |

**JQL:**
- Changes: `(<jql>) AND updated >= "-<m>m" ORDER BY updated ASC, key ASC`, where `m` = the lookback rounded up to whole minutes. With a `null` lookback, the `updated` clause is left out. Relative minutes avoid JQL's per-user timezone for absolute dates.
- Live ids: `(<jql>) ORDER BY created ASC, key ASC`. `created` never changes, so an edit can't reorder this listing.

**Change mapping:**

| `Change` field | Value |
|---|---|
| `id` | the issue's numeric `id`, which survives a move to another project, unlike the key |
| `version` | `updated` as epoch millis |
| `changedAt` | `updated` |
| `data` | the issue node |

Jira's timestamp format is `2026-10-01T10:00:00.000+0000`, parsed with the pattern `yyyy-MM-dd'T'HH:mm:ss.SSSZ`.

**Thread shape (`IssueThreads.build`):**
- **Item:** `id` is the issue id. `title` is `"<KEY>: <summary>"`, truncated to `MAX_TITLE_CHARS`. `url` is `<baseUrl>/browse/<KEY>`. `metadata` is `{key, project, type, status}`.
- **Turn `description`:** author is the reporter's `displayName`, else the creator's, else `"unknown"`. Timestamp is `created`.
- **Description text:** a header line `Type: … · Status: … · Priority: … · Assignee: … · Resolution: … · Labels: … · Components: … · Fix versions: …`, leaving out fields that are empty. Then a blank line and the description: `renderedFields.description` converted with `HtmlToMarkdown.convert(Jsoup.parseBodyFragment(html, baseUrl))`. When there's no rendered HTML and `fields.description` is a string (Data Center wiki markup), the raw string is used. An ADF object is ignored.
- **Comment turns:** id `comment:<id>`, author `author.displayName` or `"unknown"`, timestamp `created`. The text is `renderedBody` converted, else `body` when it's a string. Comments with blank text are skipped. Turns are sorted by `created`, then by id.
- **Bounds:**
  - At most `MAX_TURNS` turns. The oldest are kept, so a new comment only appends.
  - Every turn's text is capped at what remains of `MAX_TEXT_CHARS`.
  - Turns stop once the budget is spent.
  - Dropping turns writes one stderr line: `jira: <KEY> truncated to N turns`.

**Manifest:**

```toml
id = "jira"
name = "Jira"
version = "1"
protocol = 1
command = ["./pieria-harvest", "jira"]
capabilities = ["incremental", "deletes"]
network = ["the Jira site at baseUrl"]

[[config]]
key = "baseUrl"
kind = "string"
label = "Jira address"
hint = "For example https://acme.atlassian.net or https://jira.example.com"
required = true

[[config]]
key = "deployment"
kind = "enum"
label = "Deployment"
options = ["auto", "cloud", "datacenter"]
default = "auto"

[[config]]
key = "email"
kind = "string"
label = "Account email (Cloud) or username (Data Center basic auth)"
hint = "Leave empty on Data Center to use a personal access token."

[[config]]
key = "token"
kind = "secret"
label = "API token (Cloud) or personal access token (Data Center)"
required = true

[[config]]
key = "jql"
kind = "string"
label = "Which issues"
hint = "JQL without ORDER BY, for example: project = ACME AND created >= -365d"
required = true

[[config]]
key = "sweepHours"
kind = "int"
label = "Hours between deletion checks"
default = 24
```

**`JiraHarvester.check`:**
1. Parse the config.
2. `feed.begin()`.
3. Fetch the first change page, with the client's `maxResults` overridden to 1 through a small `JiraFeed.probe()` method.

A JQL error therefore surfaces as `CONFIG`, carrying Jira's message.

**`harvest`** calls `IncrementalHarvest.run(new JiraFeed(...), request, sink, config.sweepEvery(), "jira")`.

**`StubJira` behaviour.** It is built with a flag for the cloud or Data Center API, a list of issues (`id`, `key`, `summary`, `created`, `updated`, `description` HTML) and their comments.
- Every request requires the expected `Authorization` header, or it answers 401.
- It honours the following:
  - `updated >= "-Nm"`, filtering against its own `Instant.now()`;
  - `ORDER BY updated` or `ORDER BY created`;
  - paging: `startAt`/`total` on Data Center, and on cloud a `nextPageToken` holding a stringified offset;
  - comment paging.
- It can be told to change an issue's `updated` between two page requests.

- [ ] **Step 1: Write `IssueThreadsTests`.** Cover:
  - `descriptionTurnCarriesTheHeaderAndMarkdown`, asserting `Status: Done` and an `##` heading converted from `<h2>`
  - `commentsAreOrderedByCreated`
  - `newCommentOnlyAppendsATurn`: the earlier turns are equal
  - `emptyFieldsAreLeftOutOfTheHeader`
  - `adfDescriptionWithoutRenderedHtmlIsIgnored`
  - `wikiMarkupDescriptionIsUsedWhenNotRendered`
  - `tooManyCommentsKeepsTheOldest`: `MAX_TURNS + 5` comments give `MAX_TURNS` turns, ending at comment `MAX_TURNS - 1`
  - `hugeTextStaysWithinTheLimit`
  - `missingReporterFallsBackToCreatorThenUnknown`
- [ ] **Step 2: Write `JiraFeedTests`** against `StubJira`. Cover:
  - `cloudUsesSearchJqlAndNextPageToken`
  - `dataCenterUsesSearchWithStartAtOverlap`: the second request's `startAt` is 40 after a 50-row page
  - `lookbackBecomesRelativeMinutes`: 61 seconds gives `"-2m"`
  - `firstRunHasNoUpdatedClause`
  - `liveIdsOrderByCreated`
  - `commentsArePagedThroughTotal`: 150 comments take 2 requests
  - `issueDeletedBeforeLoadIsNull`
  - `jqlIsWrappedSoOrBindsInside`: `a OR b` becomes `(a OR b) AND …`
- [ ] **Step 3: Write `JiraHarvesterTests`**, using `PieriaHarvest.run` with a JSON envelope on stdin and parsing stdout with `RecordCodec`. Cover:
  - `firstHarvestReportsThreadsAndSweeps`, for cloud and for Data Center
  - `editDuringDataCenterPagingSkipsNothing` (Review Focus 1): the stub bumps issue 2's `updated` after the first page. Every issue appears as an item and in `Seen`.
  - `revokedTokenFailsAuthWithNoSweep`: exit 1, an `error` record of class `auth`, and no `sweep`
  - `checkRejectsBadJql`: the stub answers 400 with `{"errorMessages":["Field 'nope' does not exist"]}`. Exit 1, a `config` error, and the message contains `nope`.
  - `checkRejectsOrderBy`
- [ ] **Step 4: Write `JiraConformanceTests`**, the same shape as `HtmlConformanceTests`. Run `ConformanceKit` with `manifests/jira/harvester.toml`, once for a cloud `StubJira` and once for a Data Center one. Config is `{baseUrl: stub, deployment: …, email: "a@example.com" for cloud, jql: "project = T"}`, with secrets `{token: "t"}`. The invalid config omits `jql`. Expected: `report.passed()`.
- [ ] **Step 5:** Run `./gradlew :harvesters:test --tests '*jira*'`. Expected: compilation FAIL.
- [ ] **Step 6:** Implement `JiraConfig`, `IssueThreads`, `JiraFeed` and `JiraHarvester`. Add the manifest, the `PieriaHarvest` entry and the `BundledHarvesters.IDS` entry.
- [ ] **Step 7:** Run `./gradlew :harvesters:test :daemon:test --tests '*BundledHarvesters*' --tests '*DaemonNativeHints*' --tests '*HarvesterRegistry*'`. Expected: PASS.
- [ ] **Step 8:** Commit with the message `Harvest Jira issues as threads`.

---

### Task 5: The Confluence harvester

**Files:**
- Create the following in `modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/confluence/`: `StorageFormat.java`, `ConfluenceConfig.java`, `ConfluenceFeed.java`, `ConfluenceHarvester.java`
- Create: `modules/harvesters/manifests/confluence/harvester.toml`
- Modify:
  - `PieriaHarvest.java`: add `"confluence"`.
  - `BundledHarvesters.IDS`: `List.of("html", "jira", "confluence")`.
- Test: `.../confluence/StubConfluence.java`, `StorageFormatTests.java`, `ConfluenceFeedTests.java`, `ConfluenceHarvesterTests.java`, `ConfluenceConformanceTests.java`

**Interfaces:**
- Consumes: the same as Task 4, with `AtlassianSite.parse(config, secrets, "/wiki")` and label `"confluence"`.
- Produces:
  - `record ConfluenceConfig(AtlassianSite site, List<String> spaces, String cql, Duration sweepEvery)`
    - `static ConfluenceConfig parse(HarvestRequest request)`
    - At least one of `spaces` and `cql` is required, with the message `"spaces or cql is required"`.
    - Each space key must match `[A-Za-z0-9_~-]+`, so keys can't inject CQL.
    - `cql` follows the same ORDER BY rule as Jira.
    - `sweepHours` is as in Jira.
    - `String scope()` gives `type in (page, blogpost)`, then ` AND space in ("A","B")` when there are spaces, then ` AND (<cql>)` when there is CQL.
  - `final class StorageFormat`, with `static String toMarkdown(String storage, String baseUrl)`.
  - `final class ConfluenceFeed implements ChangeFeed<JsonNode>`.

**Endpoints.** The paths are relative to `baseUrl`, which already includes `/wiki` on cloud.

| Use | Endpoint and paging |
|---|---|
| begin and check | `/rest/api/user/current` |
| changes | `/rest/api/content/search?cql=<scope> AND lastmodified >= now("-<m>m") order by lastmodified asc&expand=body.storage,version,space&limit=25`. With a `null` lookback, the `lastmodified` clause is left out. |
| live ids | `/rest/api/content/search?cql=<scope> order by created asc&limit=100` |
| paging on cloud | follow `_links.next`. If it starts with `_links.context` (non-empty) followed by `/`, strip that prefix, then resolve it against `baseUrl`. A result that doesn't start with `baseUrl` gives `TRANSIENT` `"confluence returned a next link outside baseUrl"`. |
| paging on Data Center | build `start=N` yourself: `next` = `start + size - 10` while `size == limit`, otherwise `null` |

**Change mapping:**

| `Change` field | Value |
|---|---|
| `id` | the content id |
| `version` | `version.number` |
| `changedAt` | `version.when`, ISO with offset |
| `data` | the result node |

`load` makes no further request; it builds a `Document` from the result node:

| `Document` field | Value |
|---|---|
| `id` | the content id |
| `version` | `version.number` |
| `title` | `title` |
| `url` | `_links.base` (or `baseUrl`) + `_links.webui` |
| `author` | `version.by.displayName` |
| `updatedAt` | `version.when` |
| `format` | `MARKDOWN` |
| `text` | `StorageFormat.toMarkdown(body.storage.value)`, capped at `MAX_TEXT_CHARS` |
| `metadata` | `{space, type}` |

A page whose text is blank is reported anyway, with the text set to its title, so it stays live for the sweep.

**`StorageFormat` rules.** Parse with `Jsoup.parse(storage, baseUrl, Parser.xmlParser())`, rewrite, then re-parse the result as HTML and hand it to `HtmlToMarkdown.convert`.

| Storage element | Becomes |
|---|---|
| `ac:structured-macro` named `code` or `noformat` | `<pre><code>`, holding the text of its `ac:plain-text-body` (CDATA) |
| any other `ac:structured-macro` | its `ac:rich-text-body` children (unwrapped), else its `ac:plain-text-body` as `<p>`, else removed. This covers info, note, warning, panel and expand; toc, children and the jira macros are removed. |
| `ac:parameter`, `ac:image`, `ac:emoticon`, `ac:placeholder` | removed |
| `ac:link` | its `ac:link-body` or `ac:plain-text-link-body` text, else `ri:page@ri:content-title`, else `ri:attachment@ri:filename`, else `ri:url@ri:value`, else `"@" + ri:user@ri:username`, else removed |
| `ac:task-list` | `<ul>` |
| `ac:task` | `<li>`, prefixed `[x] ` when its `ac:task-status` is `complete`, else `[ ] `, holding its `ac:task-body` content |
| `time[datetime]` | the `datetime` text |
| any other element in the `ac:` or `ri:` namespace | unwrapped |

**Manifest:**
- The same shape as Jira, with `id = "confluence"`, `name = "Confluence"`, `command = ["./pieria-harvest", "confluence"]` and `network = ["the Confluence site at baseUrl"]`.
- `baseUrl` hint: `For example https://acme.atlassian.net/wiki or https://confluence.example.com`.
- Instead of `jql`, it has:
  - `spaces`, kind `list`, label `Space keys`;
  - `cql`, kind `string`, label `Extra CQL filter`, hint `Optional, without ORDER BY. Combined with the spaces using AND.`

- [ ] **Step 1: Write `StorageFormatTests`.** Cover:
  - `headingsAndParagraphsSurvive`
  - `codeMacroBecomesFencedCode`, asserting the CDATA content is kept verbatim with `<` intact
  - `infoPanelKeepsItsBody`
  - `tocMacroIsDropped`
  - `pageLinkUsesItsTitle`
  - `userLinkUsesTheUsername`
  - `taskListShowsStatus`
  - `nbspEntityParses`: `a&nbsp;b` gives no `&nbsp;` in the output
  - `tablesStillConvert`, as `HtmlToMarkdown` already handles them
- [ ] **Step 2: Write `ConfluenceFeedTests`** against `StubConfluence`. The stub requires auth and honours `lastmodified >= now("-Nm")`, both `order by` clauses, `start`/`limit` paging, and on cloud a `_links.next` carrying a `cursor` parameter and a `/wiki` context. Cover:
  - `cqlCombinesSpacesAndFilter`
  - `cloudFollowsNextLinksUnderTheWikiContext`
  - `dataCenterPagesWithStartOverlap`
  - `nextLinkOutsideBaseUrlIsTransient`
  - `lookbackBecomesRelativeMinutes`
  - `documentUsesVersionAuthorAndWebUi`
  - `blankPageStaysLive`
- [ ] **Step 3: Write `ConfluenceHarvesterTests`.** Cover:
  - `firstHarvestReportsDocumentsAndSweeps`
  - `editedPageIsReportedWithItsNewVersion`
  - `checkRejectsAnInvalidSpaceKey`, with `spaces=["A\" OR type=user"]`
  - `revokedTokenFailsAuthWithNoSweep`
- [ ] **Step 4: Write `ConfluenceConformanceTests`**, run for cloud and for Data Center. The invalid config has no spaces and no cql.
- [ ] **Step 5:** Run `./gradlew :harvesters:test --tests '*confluence*'`. Expected: compilation FAIL.
- [ ] **Step 6:** Implement the classes. Add the manifest, the `PieriaHarvest` entry and the `IDS` entry.
- [ ] **Step 7:** Run `./gradlew :harvesters:test :daemon:test --tests '*BundledHarvesters*' --tests '*HarvesterRegistry*'`. Expected: PASS.
- [ ] **Step 8:** Commit with the message `Harvest Confluence pages and blog posts`.

---

### Task 6: Docs

**Files:**
- Modify:
  - `docs/phases/phase-16-knowledge-harvesters.md`. Set "Current status" to phases 1 to 6 complete. Add an amendment block, **"Jira and Confluence, settled in phase 6"**, with one bullet for each ruling in this plan:
    - relative-minute windows with a 10-minute overlap and version dedup;
    - id sweeps every `sweepHours`, never from a run with an empty listing;
    - issue id rather than key;
    - the header line in the description turn, so status changes count as edits;
    - comments-only threads, keeping the oldest turns;
    - rendered HTML for Jira, storage format for Confluence;
    - no redirects, and private addresses allowed;
    - page and blog-post comments and attachments are out of scope for v1;
    - the in-process retry for short `Retry-After` waits.
  - `README.md`, the harvester section. List the three bundled harvesters, and add one `pieria source add jira …` example and one `pieria source add confluence …` example using `--secret-env token=JIRA_TOKEN`.
- [ ] **Step 1:** Make the edits.
- [ ] **Step 2:** Run `./gradlew test > <workspace>/full-test.log 2>&1; tail -20 <workspace>/full-test.log`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit with the message `Document the Jira and Confluence harvesters`.

---

## Manual verification (yours, after `./gradlew :daemon:deployLocal`)

1. `pieria harvester list` shows `html`, `jira` and `confluence`, each marked `(bundled)`.
2. Jira Cloud:
   1. Run `pieria source add jira --id acme --set baseUrl=https://<site>.atlassian.net --set email=<you> --set 'jql=project = <KEY>' --secret-env token=JIRA_TOKEN`.
   2. Run `source check`, then `source run --dry-run`, then a real run.
   3. Comment on an issue and re-run. Only that issue is extracted again.
   4. Delete a test issue, re-run with `sweepHours=1` after an hour, and check its memories are forgotten.
3. Repeat with Confluence on one small space. Also run one large `-Nm` lookback after a long pause, to confirm Jira and Confluence accept big relative values.
