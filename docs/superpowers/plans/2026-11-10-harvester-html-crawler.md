# Harvesters Phase 5: `pieria-harvest` and the HTML Crawler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the first real harvester end to end: a `pieria-harvest` native binary with an HTML site crawler that every install has without a separate install step, plus a per-profile recall weight for harvested memories.

**Architecture:** A new `:harvesters` module builds one binary, `pieria-harvest <id> <command>`, on the existing SDK and protocol. The crawler is stateless between runs apart from its cursor: every crawl re-checks all known pages, so a `304` still counts a page as live, and a sweep is emitted only by a crawl that saw everything. The daemon gains a second kind of harvester, *bundled*: its manifest is embedded in the daemon and its binary sits in the install's `bin/`, so it needs no install step or pin. The recall weight is one more multiplier in fusion, beside the trace boost.

**Tech Stack:** Java 25, jsoup 1.16.1, `java.net.http`, Jackson 3, GraalVM native image, JUnit 5, AssertJ. No Spring in `:harvesters`.

**Spec:** `docs/phases/phase-16-knowledge-harvesters.md`, sections "First-batch harvesters", "Developer experience", "Design calls to revisit", "Delivery phases" item 5 and "Amendments since approval". Protocol rules in the amendments are binding: `sweep` semantics, limits, exit codes.

## Global Constraints

- `./gradlew test` must pass before every commit. Never run `nativeCompile`, `nativeDist` or `deployLocal`.
- Work on branch `harvesters-phase-5`, never on `main`.
- Harvesters never touch the store or call a model. Records go through `RecordSink` only.
- A run that does not cover the whole site must not emit `sweep`.
- Blocks private and loopback addresses unless `allowPrivate`, including after redirects.
- No JS rendering in v1. Keep headings, so section splitting works.
- The crawler's User-Agent is the constant `PieriaHarvester/1.0`, and its robots token is `PieriaHarvester`. No personal address goes in either.
- Test classes use the `*Tests` suffix. No test seams in production code. `Optional` is only a return type at a lookup boundary.
- Generic helpers belong in `:shared`. `:harvester-sdk` still depends on no other Pieria module.
- Test HTTP goes to a localhost stub. CI needs no network.

## Review Focus

1. A site is down or every seed fails: the run reports a failure, emits no `sweep`, and nothing is deleted. Pinned in Task 5.
2. A page answers `304`, so its links are unknown: the pages it links to are still reported and survive the sweep. Pinned in Task 5.
3. `maxItemsPerRun` splits a crawl across runs: the final run reports the earlier runs' pages in `seen` before it sweeps. Pinned in Task 5.
4. A page redirects to a private address or an off-scope host: the redirect is not followed and no item appears under either URL. Pinned in Tasks 4 and 5.
5. A site near `maxPages` with long URLs: the cursor stays under the protocol's limits and the run does not die on its own checkpoint. Pinned in Task 5.

---

### Task 1: The `:harvesters` module and binary entry point

**Files:**
- Create: `modules/harvesters/build.gradle.kts`, `modules/harvesters/manifests/html/harvester.toml`, `modules/harvesters/src/main/java/dev/alvo/pieria/harvesters/PieriaHarvest.java`, `.../harvesters/html/HtmlHarvester.java` (stub: both methods throw `HarvesterException(FATAL, "not implemented")`)
- Modify: `settings.gradle.kts` (add `harvesters` to both lists), `docs/superpowers/plans/` (commit this plan on the branch)
- Test: `modules/harvesters/src/test/java/dev/alvo/pieria/harvesters/PieriaHarvestTests.java`

**Interfaces:**
- Produces: `PieriaHarvest.run(String[] args, InputStream in, OutputStream out, PrintStream err) : int`; `PieriaHarvest.main(String[])`, which validates args then calls `HarvesterRunner.main(harvester, new String[]{args[1]})`; `final class HtmlHarvester implements Harvester` with a no-arg constructor.

**`build.gradle.kts`:** plugins `java`, `io.spring.dependency-management`, `org.graalvm.buildtools.native`. Boot BOM `4.0.6` as in `:harvester-sdk`. `implementation(project(":harvester-sdk"))`, `implementation(project(":shared"))`, `implementation("org.jsoup:jsoup:1.16.1")`; test deps as in `:cli`. Native binary: `imageName = "pieria-harvest"`, `mainClass = "dev.alvo.pieria.harvesters.PieriaHarvest"`, build args `--enable-url-protocols=http,https`, `-H:+ReportExceptionStackTraces`, `-H:+AddAllCharsets` (real sites use more than the image's default charsets).

**Manifest, verbatim:**

```toml
id = "html"
name = "HTML site crawler"
version = "1"
protocol = 1
command = ["./pieria-harvest", "html"]
capabilities = ["incremental", "deletes"]
network = ["the hosts this source allows"]

[[config]]
key = "seeds"
kind = "list"
label = "Start URLs"
hint = "Pages to start from. Give these, sitemaps, or both."

[[config]]
key = "sitemaps"
kind = "list"
label = "Sitemap URLs"

[[config]]
key = "allowedHosts"
kind = "list"
label = "Allowed hosts"
hint = "Exact names or *.example.com. Defaults to the hosts of the start URLs and sitemaps."

[[config]]
key = "include"
kind = "list"
label = "Include patterns"
hint = "Regular expressions; a URL must match at least one, when any are given."

[[config]]
key = "exclude"
kind = "list"
label = "Exclude patterns"
hint = "Regular expressions; a URL matching any is skipped."

[[config]]
key = "maxDepth"
kind = "int"
label = "Link depth"
default = 3

[[config]]
key = "maxPages"
kind = "int"
label = "Page limit"
hint = "At most 2000. A site larger than this is never swept, so its deleted pages stay."
default = 500

[[config]]
key = "delayMillis"
kind = "int"
label = "Delay between requests to a host (ms)"
default = 1000

[[config]]
key = "allowPrivate"
kind = "bool"
label = "Allow private and loopback addresses"
default = false
```

- [ ] **Step 1: Create the branch and write the failing tests**

Run: `git switch -c harvesters-phase-5`

```java
@Test void unknownHarvesterIsAUsageError() { /* run({"nope","check"}, ...) == HarvesterRunner.EXIT_USAGE; stderr contains "html" */ }
@Test void wrongArgumentCountIsAUsageError() { /* run({"html"}, ...) == EXIT_USAGE */ }
@Test void dispatchesToTheNamedHarvester() { /* run({"html","check"}, envelope, ...) reaches the harvester: stdout has an error record, not a usage message */ }
@Test void shippedManifestParses() { /* ManifestParser.load(Path.of("manifests/html/harvester.toml")); id "html"; command ["./pieria-harvest","html"]; capabilities incremental, deletes */ }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :harvesters:test --tests '*PieriaHarvestTests'`
Expected: FAIL (classes missing).

- [ ] **Step 3: Implement the module, manifest, dispatcher and stub**

- [ ] **Step 4: Run to verify they pass**, and confirm the task exists without running it: `./gradlew help --task :harvesters:nativeCompile`
Expected: tests PASS; the help output describes `nativeCompile`.

- [ ] **Step 5: Commit**: `git add -A settings.gradle.kts modules/harvesters docs/superpowers/plans && git commit -m "Add the pieria-harvest module"`

---

### Task 2: Crawl config, URL scope and the address guard

**Files:**
- Create in `.../harvesters/html/`: `CrawlConfig.java`, `CrawlScope.java`, `AddressGuard.java`
- Test: `CrawlConfigTests`, `CrawlScopeTests`, `AddressGuardTests`

**Interfaces:**
- Produces:
  - `record CrawlConfig(List<String> seeds, List<String> sitemaps, Set<String> allowedHosts, List<Pattern> include, List<Pattern> exclude, int maxDepth, int maxPages, long delayMillis, boolean allowPrivate)`, `static CrawlConfig parse(ObjectNode config) throws HarvesterException`
  - `CrawlScope(CrawlConfig)`; `static String normalize(String url)` returning the canonical URL or `null`; `boolean allows(String normalizedUrl)`
  - `AddressGuard(boolean allowPrivate)` implements `Consumer<String>`: `accept(String host)` throws `BlockedAddressException` (unchecked, in the same package); `static boolean isBlocked(InetAddress address)`

**Rules:**
- Defaults and ranges: `maxDepth` 3 (0–50), `maxPages` 500 (1–2000), `delayMillis` 1000 (0–60000), `allowPrivate` false. Out of range, or a wrong JSON type, is `HarvesterException(CONFIG)` naming the key.
- At least one of `seeds` and `sitemaps`. Each must normalize. A bad regex is `CONFIG` naming the pattern. Seeds and sitemaps are stored normalized.
- `allowedHosts` defaults to the lowercased hosts of the seeds and sitemaps. An entry is an exact host or `*.suffix`, and `*.example.com` does not match `example.com`.
- `normalize`: http(s) only; lowercase scheme and host; drop the fragment and default port; empty path becomes `/`; keep the query; result is `URI.toASCIIString()` form; `null` for userinfo, no host, unparseable input, or over 400 characters.
- `allows`: host allowed, and any `include` matches when configured (`find` on the whole URL), and no `exclude` matches.
- `isBlocked`: loopback, any-local, link-local, site-local, multicast, `100.64.0.0/10`, IPv6 `fc00::/7`. `accept` resolves with `InetAddress.getAllByName` and throws if any address is blocked, unless `allowPrivate`. Resolution failure throws `BlockedAddressException` too, with a message saying the name did not resolve.

- [ ] **Step 1: Write the failing tests**

```java
// CrawlConfigTests
@Test void appliesDefaults() { /* {"seeds":["https://x.test/"]}: 3, 500, 1000, false; allowedHosts ["x.test"] */ }
@Test void requiresSeedsOrSitemaps() { }
@ParameterizedTest @CsvSource({"maxPages,0","maxPages,2001","maxDepth,51","delayMillis,-1"}) void rejectsOutOfRange(String key, int value) { }
@Test void rejectsAWrongType() { /* maxPages: "many" */ }
@Test void rejectsABadRegexNamingIt() { }
@Test void rejectsABadSeed() { /* "not a url", errorClass CONFIG */ }

// CrawlScopeTests
@ParameterizedTest @CsvSource({
  "HTTP://Example.COM:80/a#f, http://example.com/a",
  "https://example.com, https://example.com/",
  "https://example.com:8443/a?b=1, https://example.com:8443/a?b=1"})
void normalizes(String in, String out) { }
@ParameterizedTest @ValueSource(strings = {"ftp://x.test/", "http://u:p@x.test/", "not a url", "/relative"}) void refuses(String in) { }
@Test void refusesAnOverlongUrl() { /* 401 chars */ }
@Test void wildcardHostMatchesSubdomainsOnly() { }
@Test void includeAndExcludeApply() { }

// AddressGuardTests
@ParameterizedTest @ValueSource(strings = {"127.0.0.1","0.0.0.0","10.1.2.3","172.16.0.1","192.168.1.1","169.254.169.254","100.64.0.1","224.0.0.1","::1","fc00::1","fe80::1","::ffff:127.0.0.1"}) void blocks(String ip) { }
@ParameterizedTest @ValueSource(strings = {"8.8.8.8","2606:4700:4700::1111","100.128.0.1"}) void allowsPublic(String ip) { }
@Test void allowPrivateAdmitsLoopback() { }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :harvesters:test --tests '*CrawlConfigTests' --tests '*CrawlScopeTests' --tests '*AddressGuardTests'`
Expected: FAIL (classes missing).

- [ ] **Step 3: Implement the three classes**

- [ ] **Step 4: Run to verify they pass**. Expected: PASS.

- [ ] **Step 5: Commit**: `git add modules/harvesters && git commit -m "Add crawl config, scope and the address guard"`

---

### Task 3: HTML to Markdown

**Files:**
- Create: `.../harvesters/html/HtmlToMarkdown.java`
- Test: `HtmlToMarkdownTests`

**Interfaces:**
- Produces: `static String HtmlToMarkdown.convert(org.jsoup.nodes.Document document)`, returning blank when the page has no text.

**Rules:**
- Remove `script, style, noscript, template, nav, header, footer, aside, form, svg, iframe`, plus hidden content: `[hidden]`, `[aria-hidden=true]`, and a `style` attribute containing `display:none` or `visibility:hidden` (ignoring spaces and case). Harvested text is untrusted, and text a reader can't see has no business in a memory.
- Root is the first of `main`, `article`, `[role=main]`, otherwise `body`.
- `h1`–`h6` become `#` headings; `p`, `blockquote` (`> `), `ul`/`ol` (nested by two spaces, `-` or `1.`), `pre` (fenced), inline `code` (backticks), `a[href]` (`[text](absolute url)`, plain text when the target is not http(s)), `strong`/`b` (`**`), `em`/`i` (`*`), `br`, `hr`, and `table` (pipe rows with a separator after the first row). Images are dropped.
- Whitespace collapses in text and is preserved in `pre`. Blocks are separated by one blank line.
- Past element depth 200, the subtree is emitted as its plain text, so a pathological page can't overflow the stack.

- [ ] **Step 1: Write the failing tests**

```java
@Test void keepsHeadingLevels() { /* "<h1>A</h1><h3>B</h3>" -> "# A\n\n### B" */ }
@Test void prefersMainContent() { /* nav text and text outside <main> absent */ }
@Test void convertsNestedLists() { }
@Test void fencesPreformattedText() { /* whitespace kept */ }
@Test void makesLinksAbsolute() { /* base https://x.test/a/ , href "b" */ }
@Test void dropsJavascriptLinksToPlainText() { }
@Test void convertsTables() { }
@ParameterizedTest @ValueSource(strings = {"<p hidden>x</p>","<p aria-hidden=\"true\">x</p>","<p style=\"DISPLAY: none\">x</p>","<script>x</script>"}) void dropsHiddenText(String html) { }
@Test void aPageWithNoTextIsBlank() { }
@Test void survivesVeryDeepNesting() { /* 5000 nested divs around "deep": no StackOverflowError, contains "deep" */ }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :harvesters:test --tests '*HtmlToMarkdownTests'`
Expected: FAIL (class missing).

- [ ] **Step 3: Implement `HtmlToMarkdown`**

- [ ] **Step 4: Run to verify they pass**. Expected: PASS.

- [ ] **Step 5: Commit**: `git commit -m "Convert crawled HTML to Markdown"`

---

### Task 4: Fetching, robots.txt and sitemaps

**Files:**
- Create in `.../harvesters/html/`: `FetchResult.java`, `PageFetcher.java`, `RobotsRules.java`, `SitemapReader.java`
- Test: `StubSite.java` (test helper over `com.sun.net.httpserver.HttpServer` on `127.0.0.1:0`: `page(path, html)`, `respond(path, status, headers, body)`, `hits(path)`, `baseUrl()`), `PageFetcherTests`, `RobotsRulesTests`, `SitemapReaderTests`

**Interfaces:**
- Consumes: `AddressGuard` as `Consumer<String>` (Task 2).
- Produces:
  - `sealed interface FetchResult` with records `Page(String finalUrl, byte[] body, String contentType, String etag, String lastModified, String robotsHeader)`, `NotModified()`, `Gone()`, `Denied()`, `Skipped(String reason)`, `Failed(String reason)`, `Blocked(String reason)`, `RateLimited(long retryAfterSeconds)`
  - `PageFetcher(Consumer<String> hostCheck, long delayMillis, Duration requestDeadline)`, which `HtmlHarvester` builds with 60 seconds; `FetchResult fetch(String url, String etag, String lastModified)` (HTML only); `FetchResult fetchAny(String url)`; `void setHostDelay(String host, long millis)`
  - `RobotsRules.parse(String text, String agentToken)`, `RobotsRules.ALLOW_ALL`, `RobotsRules.DENY_ALL`, `boolean allows(String pathAndQuery)`, `long crawlDelayMillis()` (0 when none)
  - `SitemapReader(PageFetcher)`; `Result read(String sitemapUrl, int maxUrls)`; `record Result(List<String> urls, boolean complete)`

**Rules for `PageFetcher`:**
- `HttpClient` with redirects off and a 10-second connect timeout. Every hop calls `hostCheck` first, and a `BlockedAddressException` gives `Blocked`. At most 5 redirects, then `Skipped`.
- `etag` and `lastModified` go only on the first hop, as `If-None-Match` and `If-Modified-Since`.
- The body is read through a custom `BodySubscriber` capped at 10 MiB, which cancels and fails past the cap (`Skipped`). The whole exchange goes through `sendAsync(...).get(requestDeadline)` and is cancelled at the deadline (`Failed`), because `HttpRequest.timeout` stops covering the body once headers arrive.
- Status: 2xx `Page` (`fetch` returns `Skipped` unless the type is `text/html`, `application/xhtml+xml` or absent), 304 `NotModified`, 404 and 410 `Gone`, 401 and 403 `Denied`, 429 and 503 with a `Retry-After` `RateLimited` (seconds or HTTP date; a 429 without one means 300), any other non-2xx or `IOException` `Failed`.
- Waits so that requests to one host are at least `max(delayMillis, setHostDelay value)` apart. An interrupt restores the flag and gives `Failed`.

**Rules for `RobotsRules`** (RFC 9309): group chosen by exact case-insensitive token, otherwise `*`; consecutive `User-agent` lines share a group. Longest matching rule wins and `Allow` wins a tie. `*` matches any run of characters and `$` anchors the end. Only the first 500 KiB is read. `Crawl-delay` is capped at 60 seconds.

**Rules for `SitemapReader`:** parse with jsoup's XML parser, which resolves no external entities. Accept gzip by magic bytes, with the unpacked size capped at 50 MiB. `sitemapindex` nests at most 2 levels. A fetch or parse failure, or reaching `maxUrls`, gives `complete = false`. URLs are normalized and the invalid ones dropped.

- [ ] **Step 1: Write the failing tests**

```java
// PageFetcherTests (hostCheck is a no-op unless stated)
@Test void returnsAPageWithItsValidators() { }
@Test void sendsValidatorsAndReportsNotModified() { }
@Test void followsARedirectAndReportsTheFinalUrl() { }
@Test void aRedirectToABlockedHostIsNotFollowed() {
  // stub on 127.0.0.1 answers 302 to http://127.0.0.2:<port>/x; hostCheck throws BlockedAddressException for "127.0.0.2"
  // result is Blocked and the stub saw no request for /x
}
@Test void stopsAfterTooManyRedirects() { }
@ParameterizedTest @CsvSource({"404,Gone","410,Gone","401,Denied","403,Denied","500,Failed"}) void mapsStatus(int status, String type) { }
@Test void rateLimitReadsRetryAfterSeconds() { /* 429 + "120" -> RateLimited(120) */ }
@Test void rateLimitWithoutAHeaderDefaults() { /* 429 -> RateLimited(300) */ }
@Test void skipsNonHtml() { /* application/pdf */ }
@Test void skipsAnOversizedBody() { /* 11 MiB */ }
@Test void waitsBetweenRequestsToAHost() { /* delay 200ms: two fetches take >= 200ms */ }
@Test void aStalledBodyHitsTheDeadline() { /* deadline 500ms; the handler sends headers and then stalls: Failed, in under 5s */ }

// RobotsRulesTests
@Test void longestMatchWins() { }
@Test void allowWinsATie() { }
@Test void wildcardAndAnchor() { /* Disallow: /*.pdf$ */ }
@Test void prefersItsOwnGroupOverStar() { }
@Test void sharedUserAgentLines() { }
@Test void crawlDelayIsCapped() { /* Crawl-delay: 999 -> 60000 */ }

// SitemapReaderTests
@Test void readsAUrlSet() { }
@Test void followsASitemapIndex() { }
@Test void readsGzip() { }
@Test void aFailedChildIsIncomplete() { }
@Test void stopsAtTheLimitAndSaysSo() { }
@Test void malformedXmlIsIncomplete() { }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :harvesters:test --tests '*PageFetcherTests' --tests '*RobotsRulesTests' --tests '*SitemapReaderTests'`
Expected: FAIL (classes missing).

- [ ] **Step 3: Implement the four classes**

- [ ] **Step 4: Run to verify they pass**. Expected: PASS.

- [ ] **Step 5: Commit**: `git commit -m "Fetch pages politely and read robots and sitemaps"`

---

### Task 5: The crawl

**Files:**
- Create: `.../harvesters/html/CrawlCursor.java`
- Modify: `.../harvesters/html/HtmlHarvester.java`, `modules/harvester-sdk/.../protocol/ProtocolLimits.java`, `modules/harvester-sdk/src/test/.../protocol/RecordCodecTests.java` if it pins the old value
- Test: `CrawlCursorTests`, `HtmlHarvesterTests` (helper feeds an envelope through `HarvesterRunner.run` and decodes stdout with `RecordCodec.decode`), `HtmlConformanceTests`

**Interfaces:**
- Consumes: everything from Tasks 2–4.
- Produces: `CrawlCursor.read(JsonNode)` (null or unknown `v` gives an empty cursor), `JsonNode toJson()`.

**Protocol change:** `ProtocolLimits.MAX_CURSOR_CHARS` goes from `65_536` to `900_000`. The old limit can't hold a site's URL list, and the value still fits inside `MAX_ENVELOPE_BYTES`. Nothing has been released on the old value.

**Cursor wire form:** `{"v":1,"active":bool,"incomplete":bool,"pages":{"<url>":[depth,status,etag|null,lastModified|null]}}`, where status is `0` queued, `1` reported, `2` failed. `pages` keeps insertion order. An etag over 128 characters or non-ASCII is not stored. `toJson` drops every etag and last-modified if the encoding would exceed `850_000` characters. That leaves `maxPages` × (400-character URL + overhead) inside the limit, leaving room for the config in the envelope.

**The run:**

1. Parse config. If the cursor is `active`, this resumes a crawl: emit `Seen` for its status-1 URLs that are still in scope, in batches of 500.
2. Otherwise start a crawl: every page goes to queued and `incomplete` to false, out-of-scope pages are dropped, then seeds and sitemap URLs are added at depth 0. A sitemap with `complete = false` sets `incomplete`. Known pages stay queued because a `304` page yields no links, so re-checking every known page is what keeps its children reported.
3. Process queued pages in order. Before each: `robots.txt` per origin, fetched once with `fetchAny`. `Page` parses, `Gone` or `Skipped` means allow all, `Denied` means allow all (RFC 9309 treats 4xx as unrestricted), and `Failed`, `Blocked` or `RateLimited` mean `DENY_ALL`, which also sets `incomplete` and fails that page. A `Crawl-delay` calls `setHostDelay`. A disallowed page is removed.
4. Fetch with the stored validators:
   - `NotModified`: emit `Seen([url])`, status 1.
   - `Page`: normalize the final URL. If it differs and is off-scope, or already reported, remove the entry; otherwise rename the entry to the final URL. Parse with jsoup from bytes, using the response charset when given. `noindex` in a `meta[name=robots]` or the `robotsHeader` removes the entry but its links are still followed. Blank Markdown removes the entry. Otherwise, apply limits (below), then emit `Document` with id and url = final URL, `version` = `Hash.sha256Hex(markdown bytes)`, title = `<title>`, first `h1`, or the URL, cut to `MAX_TITLE_CHARS`, format `MARKDOWN`, text cut to `MAX_TEXT_CHARS`, `updatedAt` from `Last-Modified`, else `meta[property=article:modified_time]`, else now (now when the parsed value is in the future). Status 1 and store validators.
   - Links from `a[href]`, unless `nofollow`, when depth < `maxDepth`: normalized, in scope, unknown. A new URL when `pages.size() >= maxPages` is dropped and sets `incomplete`.
   - `Gone` or `Skipped`: remove the entry.
   - `Denied`, `Failed`, `Blocked`: status 2 and `incomplete`, so a revoked permission or an outage can't delete pages.
   - `RateLimited`: leave it queued, checkpoint, then throw `HarvesterException(RATE_LIMITED, ..., retryAfter)`.
5. Limits: stop before a `Document` that would pass `maxItemsPerRun`, or `maxCharsPerRun` unless it is the run's first item. The page stays queued, the run checkpoints with `active = true`, exits 0 and does not sweep.
6. Emit `Progress` after each page and a `Checkpoint` every 25 pages.
7. Finish: if no page ended with status 1, set `active = false`, checkpoint, and throw `HarvesterException(TRANSIENT, "no page could be fetched")`. Otherwise set `active = false`, checkpoint, and emit `Sweep` only if not `incomplete`.

**`check`:** parse config, then `fetchAny` the first seed or sitemap. `Page` and `NotModified` pass. `Blocked`, `Gone` and `Denied` are `CONFIG` with a clear message (`Blocked` names `allowPrivate`). `Failed` is `TRANSIENT`. `RateLimited` is `RATE_LIMITED`.

Test config always sets `allowPrivate: true` and `delayMillis: 0`, except the test that proves the guard.

- [ ] **Step 1: Write the failing tests**

```java
// CrawlCursorTests
@Test void roundTrips() { }
@Test void nullOrUnknownVersionIsEmpty() { }
@Test void aFullSiteOfLongUrlsFitsTheProtocol() {
  // 2000 pages, 400-char URLs, 128-char etags and a last-modified
  // toJson().toString().length() <= 850_000, and new HarvestRecord.Checkpoint(json) does not throw
}
@Test void dropsAnOverlongEtag() { }

// HtmlHarvesterTests
@Test void crawlsLinksAndSweeps() { /* /, /a, /b: 3 documents, last record apart from progress is Sweep */ }
@Test void documentCarriesHeadingsVersionAndDates() { /* Last-Modified maps to updatedAt; format MARKDOWN */ }
@Test void respectsDepth() { }
@Test void staysOnAllowedHosts() { }
@Test void obeysRobots() { /* disallowed page absent and never requested */ }
@Test void anUnavailableRobotsFileBlocksTheHostAndPreventsTheSweep() { /* robots 500 */ }
@Test void noindexIsNotEmittedButLinksAreFollowed() { }
@Test void nofollowStopsDiscovery() { }
@Test void readsSeedsFromASitemap() { }
@Test void aRedirectKeepsOnlyTheFinalUrl() { }
@Test void aRedirectOffScopeProducesNoItem() { }
@Test void privateAddressesAreBlockedByDefault() { /* allowPrivate false against the stub: failure record, no documents, stub saw no requests */ }

// Review Focus
@Test void anOutageFailsTheRunWithoutASweep() { /* every seed 500: exit 1, error class transient, no Sweep */ }
@Test void aDeniedPagePreventsTheSweep() { /* /a 403 after a good crawl: documents for the rest, no Sweep */ }
@Test void aNotModifiedPageKeepsItsChildrenAlive() {
  // run 1 crawls / -> /a; run 2 with the returned cursor, stub answers 304 for both
  // run 2: Seen contains both URLs, Sweep is emitted, no Document
}
@Test void aDeletedPageIsLeftOutSoTheSweepRemovesIt() { /* run 2: /a is 404; Seen or Document for / only; Sweep emitted */ }
@Test void aSplitCrawlReportsEarlierPagesBeforeSweeping() {
  // maxItemsPerRun 1 repeatedly using each run's checkpoint
  // no Sweep until the last run, whose Seen holds every page from earlier runs; union of ids is the whole site
}
@Test void maxCharsStopsBeforeTheItemExceptTheFirst() { }
@Test void aSiteOverMaxPagesIsNeverSwept() { }
@Test void rateLimitCheckpointsThenReportsRetryAfter() { /* the limited page is still queued in the checkpoint */ }
@Test void checkRejectsABadConfig() { }
@Test void checkReportsAnUnreachableSite() { }
@Test void aNonUtf8PageDecodes() { /* windows-1251 charset header */ }

// HtmlConformanceTests
@Test void passesTheConformanceKit() {
  // ConformanceTarget(ManifestParser.load(Path.of("manifests/html/harvester.toml")),
  //   List.of(java, "-cp", System.getProperty("java.class.path"), PieriaHarvest.class.getName(), "html"), Path.of(".").toAbsolutePath())
  // ConformanceOptions(config over a stable StubSite, Map.of(), {"seeds":["not a url"]}, Duration.ofSeconds(60))
  // assertThat(report.passed()).as(report.render()).isTrue()
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :harvesters:test --tests '*CrawlCursorTests' --tests '*HtmlHarvesterTests' --tests '*HtmlConformanceTests'`
Expected: FAIL.

- [ ] **Step 3: Implement `CrawlCursor`, raise the limit, implement `HtmlHarvester`**

- [ ] **Step 4: Run to verify they pass, and the SDK's own tests**

Run: `./gradlew :harvesters:test :harvester-sdk:test`
Expected: PASS.

- [ ] **Step 5: Commit**: `git commit -m "Crawl sites and report them over the harvester protocol"`

---

### Task 6: Bundled harvesters in the daemon

**Files:**
- Create: `modules/daemon/.../harvest/BundledHarvesters.java`
- Modify: `modules/daemon/build.gradle.kts` (stage manifests), `.../harvest/HarvestPaths.java`, `RegisteredHarvester.java`, `HarvesterRegistry.java`, `HarvesterInstaller.java`, `HarvestConfig.java`, `.../config/HarvestProperties.java`, `.../config/ConsoleResourceConfig.java`, `.../config/DaemonNativeHints.java`, `.../api/controller/HarvesterController.java`, `modules/shared/.../api/response/HarvesterResponse.java`, `modules/shared/.../tools/os/InstallHome.java`, `modules/cli/.../command/harvest/HarvesterListCommand.java`, `modules/daemon/src/main/resources/application.properties` if it lists harvest keys
- Test: `BundledHarvestersTests`, extend `HarvesterRegistryTests`, `HarvesterInstallerTests`, `HarvesterApiTests`, `HarvesterCommandTests`, `InstallHomeTests`, `DaemonNativeHintsTests`

**Interfaces:**
- Produces:
  - `BundledHarvesters.IDS = List.of("html")`, `static boolean isBundled(String id)`, `static HarvesterManifest manifest(String id)`, reading `/bundled-harvesters/<id>/harvester.toml` from the classpath. The list is explicit because a native image can't enumerate resources.
  - `record RegisteredHarvester(HarvesterManifest manifest, Path directory, boolean bundled)`
  - `HarvestPaths(Path root, Path bundledBin)`, `Path bundledBin()`. There is one constructor, so test call sites are updated.
  - `HarvestProperties.bundledBin` (`pieria.harvest.bundled-bin`, default blank, meaning `<install home>/bin`), appended as the last component.
  - `InstallHome.forExecutable(String executableBaseName, Function<String,String> env, Path userHome, boolean windows) : Path`: `PIERIA_HOME` if set, else the parent of the `bin` directory holding the running executable when its name is `<base>` or `<base>.exe`, else `defaultHome`. `ConsoleResourceConfig` switches to it, removing its private copy.
  - `HarvesterResponse` gains a trailing `Boolean bundled`. It is a wrapper type so a newer CLI can read an older daemon.

**Gradle (daemon):** a `stageBundledHarvesters` `Sync` task copying `modules/harvesters/manifests` into `bundled-harvesters/` under `build/generated/bundled-harvesters`, added as a main resource source dir, in the same style as the CLI's `stageHarnessAssets`.

**Rules:**
- A bundled harvester's `directory` is `bundledBin`, so the manifest's `./pieria-harvest` resolves to the shared binary.
- `find` and `list` include a bundled harvester only when its binary exists (`pieria-harvest` or `pieria-harvest.exe`). Bundled wins over an installed directory with the same id. Both must work when the harvesters root doesn't exist yet.
- `requirePinned` for a bundled harvester checks only that the binary exists, throwing `HarvesterIntegrityException` if not. It has no pin because an update replaces it along with the daemon, which carries the same trust.
- `diagnose` reports each bundled id: ok with detail `bundled`, or not ok saying the binary is missing from `bundledBin`. An installed directory with a bundled id is not ok, with detail saying the bundled harvester takes precedence.
- `HarvesterInstaller.install` throws `ConflictException` for a package whose id is bundled. `remove` throws `ConflictException` for a bundled id, saying it ships with Pieria.
- `pieria harvester list` appends ` (bundled)` when `Boolean.TRUE.equals(bundled)`.
- `DaemonNativeHints` registers the resource pattern `bundled-harvesters/*`.

- [ ] **Step 1: Write the failing tests**

```java
// BundledHarvestersTests
@Test void everyIdHasAManifestWithThatId() { }
@Test void theIdListMatchesTheManifestsDirectory() { /* Files.list of ../harvesters/manifests equals IDS, so a new harvester can't be forgotten */ }

// HarvesterRegistryTests
@Test void aBundledHarvesterIsListedWhenItsBinaryExists() { /* command resolves to <bin>/pieria-harvest html; bundled true */ }
@Test void aBundledHarvesterIsHiddenWithoutItsBinary() { }
@Test void bundledNeedsNoPin() { /* requirePinned passes with no pins.json */ }
@Test void bundledWorksBeforeTheRootExists() { /* list and diagnose */ }
@Test void bundledWinsOverAnInstalledDirectoryOfTheSameId() { }
@Test void doctorReportsAMissingBundledBinary() { }

// HarvesterInstallerTests
@Test void cannotInstallOverABundledId() { }
@Test void cannotRemoveABundledHarvester() { }

// HarvesterApiTests
@Test void listMarksBundledHarvesters() { /* jsonPath $.harvesters[0].bundled true */ }

// HarvesterCommandTests
@Test void listLabelsBundledHarvesters() { }
@Test void listReadsAnOlderDaemonWithoutTheField() { }

// InstallHomeTests
@Test void prefersPieriaHomeForExecutable() { }
@Test void fallsBackToTheDefaultWhenNotRunningFromAnInstall() { }

// DaemonNativeHintsTests
@Test void bundledManifestsAreRegisteredAsResources() { /* RuntimeHintsPredicates.resource().forResource("bundled-harvesters/html/harvester.toml") */ }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :daemon:test --tests '*harvest.*' --tests '*HarvesterApiTests' --tests '*DaemonNativeHintsTests' :cli:test --tests '*HarvesterCommandTests' :shared:test --tests '*InstallHomeTests'`
Expected: FAIL.

- [ ] **Step 3: Implement, updating `new HarvestPaths(...)`, `new HarvestProperties(...)`, `new RegisteredHarvester(...)` and `new HarvesterResponse(...)` call sites**

- [ ] **Step 4: Run to verify they pass**. Expected: PASS.

- [ ] **Step 5: Commit**: `git commit -m "Ship the crawler as a bundled harvester"`

---

### Task 7: Distribution

**Files:**
- Modify: `modules/daemon/build.gradle.kts` (`reSignAdhocMacOs` list; `nativeDist` prebuilt include list, `dependsOn`, and `from`), `.github/workflows/release.yml` (matrix row `module: harvesters`, `task: ":harvesters:nativeCompile"`, `bin: pieria-harvest`; header comment), `modules/cli/.../modules/update/BinarySource.java`, `BinarySwapper.java`, `README.md`, `AGENTS.md`, `docs/phases/phase-16-knowledge-harvesters.md`
- Test: extend `BinarySwapperTests`

**Interfaces:**
- Produces: `BinarySource.OPTIONAL_BINARIES = List.of("pieria-harvest")`. It is swapped with the same rollback when the distribution has it and skipped when it doesn't, so `pieria update` can still install a release from before this phase. `LocalDistSource` keeps requiring only `BINARIES`.

**Docs:**
- `README.md`: name `pieria-harvest` beside the other binaries, and add a short crawler example (`pieria source add html`, `run --dry-run`, `run`).
- `AGENTS.md`: add the `harvesters` module to the module list and `./gradlew :harvesters:nativeCompile` to the commands.
- Phase 16 doc: "Current status" says phases 1–5 are complete. Add an amendment block "**HTML crawler and bundling, settled in phase 5**" recording: bundled harvesters (manifest embedded, binary in `bin/`, no pin, can't be replaced or removed); every crawl re-checks all known pages because a `304` yields no links; a page no longer linked but still served stays; a denied, failed or robots-unavailable page cancels that crawl's sweep; a site over `maxPages` is never swept; `MAX_CURSOR_CHARS` is 900,000; a hostname that resolves publicly at check time and privately at connect time (DNS rebinding) is not caught, because `java.net.http` has no per-request resolver.

- [ ] **Step 1: Write the failing tests**

```java
@Test void swapsAnOptionalBinaryWhenPresent() { }
@Test void toleratesADistributionWithoutTheOptionalBinary() { }
@Test void rollsBackAnOptionalBinaryWithTheRest() { }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :cli:test --tests '*BinarySwapperTests'`
Expected: FAIL (`OPTIONAL_BINARIES` missing).

- [ ] **Step 3: Implement the swap, the Gradle and workflow changes, and the docs**

- [ ] **Step 4: Verify**

Run: `./gradlew :cli:test --tests '*BinarySwapperTests' && ./gradlew :daemon:help --task nativeDist && git grep -n "pieria-harvest" -- modules/daemon/build.gradle.kts .github/workflows/release.yml`
Expected: tests PASS, the build script configures, and `pieria-harvest` appears in the re-sign list, the prebuilt include list, the `from` block and the workflow matrix.

- [ ] **Step 5: Commit**: `git commit -m "Add pieria-harvest to the distribution"`

---

### Task 8: Recall weight for harvested memories

**Files:**
- Modify: `modules/daemon/.../retrieval/ReciprocalRankFusion.java`, `RetrievalService.java`, `.../config/PieriaProperties.java`, `EffectiveConfigResolver.java`, `ProfileConfigService.java`, `modules/shared/.../config/model/DaemonOverrides.java`, `modules/daemon/src/main/resources/application.properties`, `config/config-schema.json`, `config/pieria-default-config.toml`, `docs/phases/phase-16-knowledge-harvesters.md`
- Test: extend `ReciprocalRankFusionTests` (create if absent), `EffectiveConfigResolverTests`, `RetrievalServiceTests`; `ConfigSchemaTests` and `ConfigRecordDriftTests` guard the threading automatically

**Interfaces:**
- Produces:
  - `PieriaProperties.Retrieval.harvestedWeight` (`@DefaultValue("1.0") double`, last component, clamped to `[0,1]`); `DaemonOverrides.Retrieval.harvestedWeight` (`Double`, last component); config keys `retrieval.harvested-weight` (profile, live) and `pieria.retrieval.harvested-weight` (global, restart), kind `double`, section `rerank`, label `Harvested memory weight`, hint `Multiplies the fusion score of harvested memories. 1 changes nothing; lower values favour your own memories.`
  - `ReciprocalRankFusion(int k, Map<RetrievalChannelType, Double> weights, double traceBoost, double harvestedWeight)`. The three-argument constructor delegates with `1.0`.

**Rules:**
- The default `1.0` must leave ordering byte-identical, and the payload is not parsed at all in that case, so conversation retrieval can't regress.
- The range stops at 1 so a config can only demote harvested text, which is untrusted, and never promote it over the user's own memories.
- `0` sinks harvested memories to the bottom but leaves them available. `excludeSources` is the way to remove them.
- A memory is harvested when `MemoryOrigins.parse(payload)` is present. Its score is multiplied, and the trace boost still applies independently.
- `RetrievalService.buildPipeline` passes `cfg.harvestedWeight()`, so the per-profile value applies on each recall.
- Phase 16 doc amendment: the weight ships defaulting to `1.0`, because tuning needs a real profile with harvested data. Lowering it is a manual step after the end-to-end run.

- [ ] **Step 1: Write the failing tests**

```java
// ReciprocalRankFusionTests
@Test void aWeightOfOneChangesNothing() { }
@Test void lowersHarvestedMemories() { /* harvested at rank 1 and own at rank 2 in one channel; weight 0.1: own first */ }
@Test void zeroSinksButKeepsHarvestedMemories() { /* still in the result, last */ }
@Test void leavesOwnMemoriesAlone() { /* an own memory's score is identical with weight 0.5 and 1.0 */ }
@Test void anOpaquePayloadIsNotHarvested() { }

// EffectiveConfigResolverTests
@Test void aProfileCanOverrideTheHarvestedWeight() { }
@Test void theHarvestedWeightIsClamped() { /* 5.0 -> 1.0, -1 -> 0.0 */ }

// RetrievalServiceTests
@Test void aProfilesHarvestedWeightReordersItsRecall() { }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :daemon:test --tests '*ReciprocalRankFusionTests' --tests '*EffectiveConfigResolverTests' --tests '*RetrievalServiceTests'`
Expected: FAIL (compile errors on the new constructor and accessor).

- [ ] **Step 3: Implement, appending the new value to every positional `new PieriaProperties.Retrieval(...)` and `new DaemonOverrides.Retrieval(...)` call site**

- [ ] **Step 4: Run the full suite**

Run: `./gradlew test`
Expected: PASS, including `ConfigSchemaTests` and `ConfigRecordDriftTests`.

- [ ] **Step 5: Commit**: `git commit -m "Let a profile weigh harvested memories in recall"`

---

## Manual verification (the user runs these)

Native builds are out of scope for the executor. After merging:

1. `./gradlew :daemon:deployLocal`.
2. `pieria harvester list` shows `html ... (bundled)`, and `pieria harvester doctor` is healthy.
3. Add a crawler source for a small docs site, then `pieria source run <id> --dry-run`, then run it for real.
4. Edit a page and re-run: recall shows the new fact with its untrusted label, and `asOf` still shows the old one.
5. Remove the page and re-run: its memories are forgotten.
6. If the source crowds out your own memories in recall, lower `retrieval.harvested-weight` for that profile and compare.
