package dev.alvo.pieria.harvesters.html;

import dev.alvo.pieria.harvester.HarvesterRunner;
import dev.alvo.pieria.harvester.protocol.DocumentFormat;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.RecordCodec;
import dev.alvo.pieria.harvester.protocol.RequestCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlHarvesterTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private StubSite site;

  @BeforeEach
  void setUp() {
    site = StubSite.start();
  }

  @AfterEach
  void tearDown() {
    site.close();
  }

  private record Run(int code, List<HarvestRecord> records, String err) {
    List<HarvestRecord.Document> documents() {
      return records.stream().filter(HarvestRecord.Document.class::isInstance).map(HarvestRecord.Document.class::cast).toList();
    }

    List<String> documentIds() {
      return documents().stream().map(HarvestRecord.Document::id).toList();
    }

    List<String> seen() {
      return records.stream().filter(HarvestRecord.Seen.class::isInstance).map(HarvestRecord.Seen.class::cast)
        .flatMap(seen -> seen.ids().stream()).toList();
    }

    boolean swept() {
      return records.stream().anyMatch(HarvestRecord.Sweep.class::isInstance);
    }

    JsonNode cursor() {
      return records.stream().filter(HarvestRecord.Checkpoint.class::isInstance).map(HarvestRecord.Checkpoint.class::cast)
        .reduce((_, last) -> last).map(HarvestRecord.Checkpoint::cursor).orElse(null);
    }

    HarvestRecord.Failure failure() {
      return records.stream().filter(HarvestRecord.Failure.class::isInstance).map(HarvestRecord.Failure.class::cast).findFirst().orElse(null);
    }
  }

  private static Run execute(String command, ObjectNode config, JsonNode cursor, HarvestRequest.Limits limits) {
    String envelope = RequestCodec.encode(new HarvestRequest(1, config, Map.of(), cursor, limits)) + "\n";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = HarvesterRunner.run(new HtmlHarvester(), new String[]{command}, new ByteArrayInputStream(envelope.getBytes(StandardCharsets.UTF_8)),
      out, new PrintStream(err));
    List<HarvestRecord> records = new ArrayList<>();
    out.toString(StandardCharsets.UTF_8).lines().forEach(line -> records.add(RecordCodec.decode(line)));
    return new Run(code, records, err.toString(StandardCharsets.UTF_8));
  }

  private Run run(ObjectNode config, JsonNode cursor, HarvestRequest.Limits limits) {
    return execute("harvest", config, cursor, limits);
  }

  private Run run(ObjectNode config, JsonNode cursor) {
    return run(config, cursor, null);
  }

  private Run run(ObjectNode config) {
    return run(config, null, null);
  }

  private ObjectNode config(String... seedPaths) {
    ObjectNode config = MAPPER.createObjectNode().put("allowPrivate", true).put("delayMillis", 0);
    var seeds = config.putArray("seeds");
    for (String path : seedPaths) {
      seeds.add(site.baseUrl() + path);
    }
    return config;
  }

  private String url(String path) {
    return site.baseUrl() + path;
  }

  private static String links(String... paths) {
    StringBuilder html = new StringBuilder("<h1>Page</h1><p>Body text</p>");
    for (String path : paths) {
      html.append("<a href=\"").append(path).append("\">link</a> ");
    }
    return html.toString();
  }

  private void threePages() {
    site.page("/", links("/a", "/b"));
    site.page("/a", links("/"));
    site.page("/b", links());
  }

  @Test
  void crawlsLinksAndSweeps() {
    threePages();

    Run run = run(config("/"));

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.documentIds()).containsExactlyInAnyOrder(url("/"), url("/a"), url("/b"));
    assertThat(run.records().stream().filter(record -> !(record instanceof HarvestRecord.Progress)).toList().getLast())
      .isInstanceOf(HarvestRecord.Sweep.class);
  }

  @Test
  void documentCarriesHeadingsVersionAndDates() {
    site.respond("/", 200, Map.of("Content-Type", "text/html", "Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT"),
      "<html><head><title>Hello</title></head><body><h1>Top</h1><h2>Sub</h2><p>Text</p></body></html>");

    HarvestRecord.Document document = run(config("/")).documents().getFirst();

    assertThat(document.title()).isEqualTo("Hello");
    assertThat(document.format()).isEqualTo(DocumentFormat.MARKDOWN);
    assertThat(document.text()).contains("# Top").contains("## Sub");
    assertThat(document.version()).hasSize(64);
    assertThat(document.updatedAt()).isEqualTo(Instant.parse("2026-10-21T07:28:00Z"));
    assertThat(document.url()).isEqualTo(document.id());
  }

  @Test
  void theVersionFollowsTheContent() {
    site.page("/", "<p>one</p>");
    String first = run(config("/")).documents().getFirst().version();

    assertThat(run(config("/")).documents().getFirst().version()).isEqualTo(first);

    site.page("/", "<p>two</p>");
    assertThat(run(config("/")).documents().getFirst().version()).isNotEqualTo(first);
  }

  @Test
  void aMetaDateIsUsedWhenThereIsNoHeader() {
    site.page("/", "<head><meta property=\"article:modified_time\" content=\"2026-01-02T03:04:05Z\"></head><p>x</p>");

    assertThat(run(config("/")).documents().getFirst().updatedAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
  }

  @Test
  void aFutureDateBecomesNow() {
    site.page("/", "<head><meta property=\"article:modified_time\" content=\"2099-01-02T03:04:05Z\"></head><p>x</p>");

    assertThat(run(config("/")).documents().getFirst().updatedAt()).isBeforeOrEqualTo(Instant.now());
  }

  @Test
  void aPageWithNoTitleUsesItsHeading() {
    site.page("/", "<h1>Heading</h1><p>x</p>");

    assertThat(run(config("/")).documents().getFirst().title()).isEqualTo("Heading");
  }

  @Test
  void respectsDepth() {
    site.page("/", links("/a"));
    site.page("/a", links("/b"));
    site.page("/b", links());

    Run run = run(config("/").put("maxDepth", 1));

    assertThat(run.documentIds()).containsExactlyInAnyOrder(url("/"), url("/a"));
    assertThat(site.hits("/b")).isZero();
  }

  @Test
  void staysOnAllowedHosts() {
    site.page("/", links("http://localhost:" + site.port() + "/other"));
    site.page("/other", links());

    Run run = run(config("/"));

    assertThat(run.documentIds()).containsExactly(url("/"));
    assertThat(site.hits("/other")).isZero();
  }

  @Test
  void excludePatternsApplyToLinks() {
    site.page("/", links("/a", "/skip"));
    site.page("/a", links());
    site.page("/skip", links());
    ObjectNode config = config("/");
    config.putArray("exclude").add("/skip$");

    assertThat(run(config).documentIds()).containsExactlyInAnyOrder(url("/"), url("/a"));
    assertThat(site.hits("/skip")).isZero();
  }

  @Test
  void obeysRobots() {
    site.respond("/robots.txt", 200, Map.of("Content-Type", "text/plain"), "User-agent: *\nDisallow: /secret\n");
    site.page("/", links("/secret", "/a"));
    site.page("/secret", links());
    site.page("/a", links());

    Run run = run(config("/"));

    assertThat(run.documentIds()).containsExactlyInAnyOrder(url("/"), url("/a"));
    assertThat(site.hits("/secret")).isZero();
    assertThat(run.swept()).isTrue();
  }

  @Test
  void robotsIsFetchedOncePerHost() {
    threePages();

    run(config("/"));

    assertThat(site.hits("/robots.txt")).isEqualTo(1);
  }

  @Test
  void anUnavailableRobotsFileBlocksTheHostAndPreventsTheSweep() {
    site.respond("/robots.txt", 500, Map.of(), "x");
    threePages();

    Run run = run(config("/"));

    assertThat(run.documents()).isEmpty();
    assertThat(site.hits("/")).isZero();
    assertThat(run.swept()).isFalse();
  }

  @Test
  void noindexIsNotEmittedButLinksAreFollowed() {
    site.page("/", "<head><meta name=\"robots\" content=\"noindex\"></head>" + links("/a"));
    site.page("/a", links());

    assertThat(run(config("/")).documentIds()).containsExactly(url("/a"));
  }

  @Test
  void anXRobotsHeaderCounts() {
    site.respond("/", 200, Map.of("Content-Type", "text/html", "X-Robots-Tag", "noindex"), links("/a"));
    site.page("/a", links());

    assertThat(run(config("/")).documentIds()).containsExactly(url("/a"));
  }

  @Test
  void nofollowStopsDiscovery() {
    site.page("/", "<head><meta name=\"robots\" content=\"nofollow\"></head>" + links("/a"));
    site.page("/a", links());

    assertThat(run(config("/")).documentIds()).containsExactly(url("/"));
    assertThat(site.hits("/a")).isZero();
  }

  @Test
  void readsSeedsFromASitemap() {
    site.respond("/s.xml", 200, Map.of("Content-Type", "application/xml"),
      "<urlset><url><loc>" + url("/a") + "</loc></url></urlset>");
    site.page("/a", links());
    ObjectNode config = MAPPER.createObjectNode().put("allowPrivate", true).put("delayMillis", 0);
    config.putArray("sitemaps").add(url("/s.xml"));

    Run run = run(config);

    assertThat(run.documentIds()).containsExactly(url("/a"));
    assertThat(run.swept()).isTrue();
  }

  @Test
  void anUnreadableSitemapPreventsTheSweep() {
    site.page("/", links());
    ObjectNode config = config("/");
    config.putArray("sitemaps").add(url("/gone.xml"));

    Run run = run(config);

    assertThat(run.documentIds()).containsExactly(url("/"));
    assertThat(run.swept()).isFalse();
  }

  @Test
  void aRedirectKeepsOnlyTheFinalUrl() {
    site.page("/", links("/old"));
    site.respond("/old", 301, Map.of("Location", "/new"), "");
    site.page("/new", links());

    assertThat(run(config("/")).documentIds()).containsExactlyInAnyOrder(url("/"), url("/new"));
  }

  @Test
  void aRedirectToAKnownPageProducesNoDuplicate() {
    site.page("/", links("/a", "/alias"));
    site.page("/a", links());
    site.respond("/alias", 301, Map.of("Location", "/a"), "");

    assertThat(run(config("/")).documentIds()).containsExactlyInAnyOrder(url("/"), url("/a"));
  }

  @Test
  void aRedirectOffScopeProducesNoItem() {
    site.page("/", links("/login"));
    site.respond("/login", 302, Map.of("Location", "http://localhost:" + site.port() + "/sso"), "");
    site.page("/sso", links());

    Run run = run(config("/"));

    assertThat(run.documentIds()).containsExactly(url("/"));
    assertThat(site.hits("/sso")).isZero();
    assertThat(run.swept()).as("a login redirect must not cancel deletions forever").isTrue();
  }

  @Test
  void privateAddressesAreBlockedByDefault() {
    site.page("/", links());

    Run run = run(config("/").put("allowPrivate", false));

    assertThat(run.code()).isEqualTo(HarvesterRunner.EXIT_FAILED);
    assertThat(run.documents()).isEmpty();
    assertThat(run.failure().message()).contains("private");
    assertThat(site.hits("/")).isZero();
    assertThat(site.hits("/robots.txt")).isZero();
  }

  @Test
  void anOutageFailsTheRunWithoutASweep() {
    site.respond("/", 500, Map.of(), "x");

    Run run = run(config("/"));

    assertThat(run.code()).isEqualTo(HarvesterRunner.EXIT_FAILED);
    assertThat(run.failure().errorClass()).isEqualTo(ErrorClass.TRANSIENT);
    assertThat(run.swept()).isFalse();
  }

  @Test
  void aDeniedPagePreventsTheSweep() {
    site.page("/", links("/a", "/b"));
    site.respond("/a", 403, Map.of(), "x");
    site.page("/b", links());

    Run run = run(config("/"));

    assertThat(run.code()).isZero();
    assertThat(run.documentIds()).containsExactlyInAnyOrder(url("/"), url("/b"));
    assertThat(run.swept()).isFalse();
  }

  @Test
  void aFailedPagePreventsTheSweep() {
    site.page("/", links("/a"));
    site.respond("/a", 500, Map.of(), "x");

    assertThat(run(config("/")).swept()).isFalse();
  }

  @Test
  void aNotModifiedPageKeepsItsChildrenAlive() {
    site.respond("/", 200, Map.of("Content-Type", "text/html", "ETag", "\"r\""), links("/a"));
    site.respond("/a", 200, Map.of("Content-Type", "text/html", "ETag", "\"a\""), links());
    JsonNode cursor = run(config("/")).cursor();
    site.respond("/", 304, Map.of(), "");
    site.respond("/a", 304, Map.of(), "");

    Run second = run(config("/"), cursor);

    assertThat(second.documents()).isEmpty();
    assertThat(second.seen()).containsExactlyInAnyOrder(url("/"), url("/a"));
    assertThat(second.swept()).isTrue();
    assertThat(site.lastHeader("/a", "If-None-Match")).isEqualTo("\"a\"");
  }

  @Test
  void aDeletedPageIsLeftOutSoTheSweepRemovesIt() {
    site.page("/", links("/a"));
    site.page("/a", links());
    JsonNode cursor = run(config("/")).cursor();
    site.respond("/a", 404, Map.of(), "");

    Run second = run(config("/"), cursor);

    assertThat(second.documentIds()).containsExactly(url("/"));
    assertThat(second.seen()).doesNotContain(url("/a"));
    assertThat(second.swept()).isTrue();
  }

  @Test
  void aPageThatLeftTheScopeIsDropped() {
    site.page("/", links("/a"));
    site.page("/a", links());
    JsonNode cursor = run(config("/")).cursor();
    ObjectNode narrowed = config("/");
    narrowed.putArray("exclude").add("/a$");

    Run second = run(narrowed, cursor);

    assertThat(second.documentIds()).containsExactly(url("/"));
    assertThat(second.swept()).isTrue();
  }

  @Test
  void aSplitCrawlReportsEarlierPagesBeforeSweeping() {
    threePages();
    HarvestRequest.Limits oneAtATime = new HarvestRequest.Limits(1, null);
    Set<String> reported = new HashSet<>();
    JsonNode cursor = null;
    Run last = null;
    for (int i = 0; i < 3; i++) {
      last = run(config("/"), cursor, oneAtATime);
      assertThat(last.code()).as(last.err()).isZero();
      assertThat(last.documents()).hasSize(1);
      assertThat(last.swept()).isEqualTo(i == 2);
      reported.addAll(last.documentIds());
      cursor = last.cursor();
    }

    assertThat(reported).containsExactlyInAnyOrder(url("/"), url("/a"), url("/b"));
    Set<String> covered = new HashSet<>(last.seen());
    covered.addAll(last.documentIds());
    assertThat(covered).as("a sweep treats anything unreported as deleted").containsExactlyInAnyOrder(url("/"), url("/a"), url("/b"));
  }

  @Test
  void aStoppedRunLeavesItsPageForTheNext() {
    threePages();

    Run first = run(config("/"), null, new HarvestRequest.Limits(1, null));

    assertThat(first.swept()).isFalse();
    assertThat(CrawlCursor.read(first.cursor()).active()).isTrue();
  }

  @Test
  void maxCharsStopsBeforeTheItemExceptTheFirst() {
    site.page("/", "<p>" + "x".repeat(100) + "</p><a href=\"/a\">a</a>");
    site.page("/a", "<p>" + "y".repeat(100) + "</p>");

    Run run = run(config("/"), null, new HarvestRequest.Limits(null, 10L));

    assertThat(run.documents()).hasSize(1);
    assertThat(run.swept()).isFalse();
    assertThat(run.code()).isZero();
  }

  @Test
  void aSiteOverMaxPagesIsNeverSwept() {
    threePages();

    Run run = run(config("/").put("maxPages", 2));

    assertThat(run.documents()).hasSize(2);
    assertThat(run.swept()).isFalse();
  }

  @Test
  void loweringMaxPagesDoesNotSweepTheRest() {
    threePages();
    JsonNode cursor = run(config("/")).cursor();

    Run run = run(config("/").put("maxPages", 1), cursor);

    assertThat(run.swept()).isFalse();
  }

  @Test
  void rateLimitCheckpointsThenReportsRetryAfter() {
    site.page("/", links("/a"));
    site.respond("/a", 429, Map.of("Retry-After", "120"), "");

    Run run = run(config("/"));

    assertThat(run.code()).isEqualTo(HarvesterRunner.EXIT_FAILED);
    assertThat(run.failure().errorClass()).isEqualTo(ErrorClass.RATE_LIMITED);
    assertThat(run.failure().retryAfterSeconds()).isEqualTo(120);
    CrawlCursor cursor = CrawlCursor.read(run.cursor());
    assertThat(cursor.active()).isTrue();
    assertThat(cursor.pages().get(url("/a")).status).isEqualTo(CrawlCursor.Status.QUEUED);
    assertThat(run.swept()).isFalse();
  }

  @Test
  void aBlankPageIsNotAnItem() {
    site.page("/", links("/blank"));
    site.page("/blank", "<script>x</script>");

    assertThat(run(config("/")).documentIds()).containsExactly(url("/"));
  }

  @Test
  void nonHtmlIsSkippedWithoutCancellingTheSweep() {
    site.page("/", links("/file.pdf"));
    site.respond("/file.pdf", 200, Map.of("Content-Type", "application/pdf"), "x");

    Run run = run(config("/"));

    assertThat(run.documentIds()).containsExactly(url("/"));
    assertThat(run.swept()).isTrue();
  }

  @Test
  void aNonUtf8PageDecodes() {
    site.respond("/", 200, Map.of("Content-Type", "text/html; charset=windows-1251"),
      "<p>Привет</p>".getBytes(Charset.forName("windows-1251")));

    assertThat(run(config("/")).documents().getFirst().text()).contains("Привет");
  }

  @Test
  void anUnknownCharsetFallsBack() {
    site.respond("/", 200, Map.of("Content-Type", "text/html; charset=nonsense"), "<p>hello</p>");

    assertThat(run(config("/")).documents().getFirst().text()).contains("hello");
  }

  @Test
  void aHugePageIsCutToTheProtocolLimit() {
    site.page("/", "<p>" + "x ".repeat(1_100_000) + "</p>");

    Run run = run(config("/"));

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(run.documents().getFirst().text().length()).isLessThanOrEqualTo(2_000_000);
  }

  @Test
  void checkPassesForAReachableSite() {
    site.page("/", links());

    assertThat(execute("check", config("/"), null, null).code()).isZero();
  }

  @Test
  void checkRejectsABadConfig() {
    ObjectNode config = MAPPER.createObjectNode();
    config.putArray("seeds").add("not a url");

    Run run = execute("check", config, null, null);

    assertThat(run.code()).isEqualTo(HarvesterRunner.EXIT_FAILED);
    assertThat(run.failure().errorClass()).isEqualTo(ErrorClass.CONFIG);
  }

  @Test
  void checkReportsABlockedAddressAsConfig() {
    site.page("/", links());

    Run run = execute("check", config("/").put("allowPrivate", false), null, null);

    assertThat(run.failure().errorClass()).isEqualTo(ErrorClass.CONFIG);
    assertThat(run.failure().message()).contains("allowPrivate");
  }

  @Test
  void checkReportsAnUnreachableSite() {
    site.respond("/", 500, Map.of(), "x");

    assertThat(execute("check", config("/"), null, null).failure().errorClass()).isEqualTo(ErrorClass.TRANSIENT);
  }

  @Test
  void checkReportsAMissingStartPage() {
    assertThat(execute("check", config("/absent"), null, null).failure().errorClass()).isEqualTo(ErrorClass.CONFIG);
  }
}
