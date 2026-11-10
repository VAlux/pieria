package dev.alvo.pieria.harvesters.html;

import dev.alvo.pieria.harvester.Harvester;
import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.RecordSink;
import dev.alvo.pieria.harvester.protocol.DocumentFormat;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;
import dev.alvo.pieria.tools.Hash;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crawls a website. Every crawl re-checks all the pages it already knows, because a page answering
 * {@code 304} yields no links, and its children would otherwise look deleted. A crawl that couldn't
 * see the whole site never emits a sweep, so an outage or lost permission can't delete anything.
 */
public final class HtmlHarvester implements Harvester {

  private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(60);
  private static final String ROBOTS_TOKEN = "PieriaHarvester";
  private static final int CHECKPOINT_EVERY = 25;
  private static final int SEEN_BATCH = 500;
  private static final Pattern CHARSET = Pattern.compile("charset=[\"']?([^;\"'\\s]+)", Pattern.CASE_INSENSITIVE);

  private static PageFetcher fetcher(CrawlConfig config) {
    return new PageFetcher(new AddressGuard(config.allowPrivate()), config.delayMillis(), REQUEST_DEADLINE);
  }

  @Override
  public void check(HarvestRequest request, RecordSink sink) throws HarvesterException {
    CrawlConfig config = CrawlConfig.parse(request.config());
    String first = config.seeds().isEmpty() ? config.sitemaps().getFirst() : config.seeds().getFirst();
    // A seed must be a page the crawl can use; a sitemap is any document.
    PageFetcher fetcher = fetcher(config);
    switch (config.seeds().isEmpty() ? fetcher.fetchAny(first) : fetcher.fetch(first, null, null)) {
      case FetchResult.Page _, FetchResult.NotModified _ -> {
      }
      case FetchResult.Blocked blocked -> throw new HarvesterException(ErrorClass.CONFIG, blocked.reason());
      case FetchResult.Gone _ -> throw new HarvesterException(ErrorClass.CONFIG, first + " was not found");
      case FetchResult.Denied _ -> throw new HarvesterException(ErrorClass.CONFIG, first + " refused access");
      case FetchResult.Skipped skipped -> throw new HarvesterException(ErrorClass.CONFIG, skipped.reason());
      case FetchResult.Failed failed -> throw new HarvesterException(ErrorClass.TRANSIENT, failed.reason());
      case FetchResult.RateLimited limited ->
        throw new HarvesterException(ErrorClass.RATE_LIMITED, first + " is rate limiting requests", limited.retryAfterSeconds());
    }
  }

  @Override
  public void harvest(HarvestRequest request, RecordSink sink) throws HarvesterException {
    new Crawl(request, sink).run();
  }

  /** One run of the crawler. */
  private static final class Crawl {

    private final CrawlConfig config;
    private final CrawlScope scope;
    private final PageFetcher fetcher;
    private final RecordSink sink;
    private final HarvestRequest.Limits limits;
    private final CrawlCursor cursor;
    private final Map<String, CrawlCursor.Page> pages;
    private final Deque<String> queue = new ArrayDeque<>();
    private final Map<String, RobotsRules> robots = new HashMap<>();
    private int emitted;
    private long chars;
    private int processed;
    private String firstProblem;

    Crawl(HarvestRequest request, RecordSink sink) throws HarvesterException {
      this.config = CrawlConfig.parse(request.config());
      this.scope = new CrawlScope(config);
      this.fetcher = fetcher(config);
      this.fetcher.restrictRedirects(scope::allows);
      this.sink = sink;
      this.limits = request.limits();
      this.cursor = CrawlCursor.read(request.cursor());
      this.pages = cursor.pages();
    }

    void run() throws HarvesterException {
      pages.keySet().removeIf(url -> !scope.allows(url));
      if (cursor.active()) {
        reportEarlierPages();
      } else {
        beginCrawl();
      }
      pages.forEach((url, page) -> {
        if (page.status == CrawlCursor.Status.QUEUED) {
          queue.add(url);
        }
      });
      for (String url = queue.poll(); url != null; url = queue.poll()) {
        CrawlCursor.Page page = pages.get(url);
        if (page == null || page.status != CrawlCursor.Status.QUEUED) {
          continue;
        }
        if (!process(url, page)) {
          checkpoint();
          return;
        }
        afterPage();
      }
      finish();
    }

    /** A sweep treats anything unreported in its own run as deleted, so a resumed run repeats what came before. */
    private void reportEarlierPages() {
      List<String> batch = new ArrayList<>();
      pages.forEach((url, page) -> {
        if (page.status == CrawlCursor.Status.REPORTED) {
          batch.add(url);
        }
      });
      for (int from = 0; from < batch.size(); from += SEEN_BATCH) {
        sink.emit(new HarvestRecord.Seen(List.copyOf(batch.subList(from, Math.min(from + SEEN_BATCH, batch.size())))));
      }
    }

    private void beginCrawl() {
      cursor.restart();
      cursor.active(true);
      if (pages.size() > config.maxPages()) {
        List<String> excess = new ArrayList<>(pages.keySet()).subList(config.maxPages(), pages.size());
        excess.forEach(pages::remove);
        cursor.markIncomplete();
      }
      config.seeds().forEach(seed -> add(seed, 0));
      SitemapReader sitemaps = new SitemapReader(fetcher);
      for (String sitemap : config.sitemaps()) {
        SitemapReader.Result result = sitemaps.read(sitemap, config.maxPages());
        if (!result.complete()) {
          problem("the sitemap " + sitemap + " could not be read in full");
        }
        result.urls().forEach(url -> add(url, 0));
      }
    }

    private void add(String url, int depth) {
      if (!scope.allows(url)) {
        return;
      }
      CrawlCursor.Page known = pages.get(url);
      if (known != null) {
        known.depth = Math.min(known.depth, depth);
        return;
      }
      if (pages.size() >= config.maxPages()) {
        cursor.markIncomplete();
        return;
      }
      pages.put(url, new CrawlCursor.Page(depth));
      queue.add(url);
    }

    private void problem(String message) {
      cursor.markIncomplete();
      if (firstProblem == null) {
        firstProblem = message;
      }
      System.err.println("html: " + message);
    }

    /** Returns false when a run limit means this run must stop before the page. */
    private boolean process(String url, CrawlCursor.Page page) throws HarvesterException {
      RobotsRules rules = rulesFor(url);
      if (rules == RobotsRules.DENY_ALL) {
        page.status = CrawlCursor.Status.FAILED;
        return true;
      }
      if (!rules.allows(pathOf(url))) {
        pages.remove(url);
        return true;
      }
      switch (fetcher.fetch(url, page.etag, page.lastModified)) {
        case FetchResult.NotModified _ -> {
          sink.emit(new HarvestRecord.Seen(List.of(url)));
          page.status = CrawlCursor.Status.REPORTED;
        }
        case FetchResult.Page fetched -> {
          return accept(url, page, fetched);
        }
        case FetchResult.Gone _, FetchResult.Skipped _ -> pages.remove(url);
        case FetchResult.Denied _ -> fail(page, url + " refused access");
        case FetchResult.Failed failed -> fail(page, failed.reason());
        case FetchResult.Blocked blocked -> fail(page, blocked.reason());
        case FetchResult.RateLimited limited -> {
          checkpoint();
          throw new HarvesterException(ErrorClass.RATE_LIMITED, url + " is rate limiting requests", limited.retryAfterSeconds());
        }
      }
      return true;
    }

    private void fail(CrawlCursor.Page page, String reason) {
      page.status = CrawlCursor.Status.FAILED;
      problem(reason);
    }

    private RobotsRules rulesFor(String url) {
      URI uri = URI.create(url);
      String origin = uri.getScheme() + "://" + uri.getRawAuthority();
      return robots.computeIfAbsent(origin, _ -> {
        RobotsRules rules = loadRobots(origin);
        if (rules.crawlDelayMillis() > 0) {
          fetcher.setHostDelay(uri.getHost(), rules.crawlDelayMillis());
        }
        return rules;
      });
    }

    private RobotsRules loadRobots(String origin) {
      String location = origin + "/robots.txt";
      return switch (fetcher.fetchAny(location)) {
        case FetchResult.Page page -> RobotsRules.parse(new String(page.body(), StandardCharsets.UTF_8), ROBOTS_TOKEN);
        case FetchResult.Gone _, FetchResult.Skipped _, FetchResult.Denied _, FetchResult.NotModified _ -> RobotsRules.ALLOW_ALL;
        case FetchResult.Failed failed -> unavailableRobots(location + ": " + failed.reason());
        case FetchResult.Blocked blocked -> unavailableRobots(blocked.reason());
        case FetchResult.RateLimited _ -> unavailableRobots(location + " is rate limiting requests");
      };
    }

    private RobotsRules unavailableRobots(String reason) {
      problem(reason);
      return RobotsRules.DENY_ALL;
    }

    private static String pathOf(String url) {
      URI uri = URI.create(url);
      return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    private boolean accept(String url, CrawlCursor.Page original, FetchResult.Page fetched) throws HarvesterException {
      String finalUrl = fetched.finalUrl();
      CrawlCursor.Page page = original;
      if (!finalUrl.equals(url)) {
        pages.remove(url);
        CrawlCursor.Page known = pages.get(finalUrl);
        if ((known != null && known.status == CrawlCursor.Status.REPORTED) || !rulesFor(finalUrl).allows(pathOf(finalUrl))) {
          return true;
        }
        if (known != null) {
          known.depth = Math.min(known.depth, original.depth);
          page = known;
        } else {
          pages.put(finalUrl, original);
        }
      }
      Document document = parse(fetched);
      if (document == null) {
        pages.remove(finalUrl);
        return true;
      }
      String directives = directives(document, fetched);
      boolean indexable = !(directives.contains("noindex") || directives.contains("none"));
      String markdown = indexable ? HtmlToMarkdown.convert(document) : "";
      if (markdown.isBlank()) {
        pages.remove(finalUrl);
      } else if (!report(finalUrl, page, fetched, document, markdown)) {
        return false;
      }
      if (!(directives.contains("nofollow") || directives.contains("none")) && page.depth < config.maxDepth()) {
        for (Element link : document.select("a[href]")) {
          String target = CrawlScope.normalize(link.absUrl("href"));
          if (target != null) {
            add(target, page.depth + 1);
          }
        }
      }
      return true;
    }

    /** Returns false, leaving the page queued, when emitting it would break a run limit. */
    private boolean report(String url, CrawlCursor.Page page, FetchResult.Page fetched, Document document, String markdown) {
      String text = markdown.length() > ProtocolLimits.MAX_TEXT_CHARS ? markdown.substring(0, ProtocolLimits.MAX_TEXT_CHARS) : markdown;
      boolean itemLimit = limits.maxItemsPerRun() != null && emitted >= limits.maxItemsPerRun();
      boolean charLimit = limits.maxCharsPerRun() != null && emitted > 0 && chars + text.length() > limits.maxCharsPerRun();
      if (itemLimit || charLimit) {
        return false;
      }
      sink.emit(new HarvestRecord.Document(url, Hash.sha256Hex(text.getBytes(StandardCharsets.UTF_8)), title(document, url), url, null,
        updatedAt(fetched, document), DocumentFormat.MARKDOWN, text, null));
      emitted++;
      chars += text.length();
      page.status = CrawlCursor.Status.REPORTED;
      page.validators(fetched.etag(), fetched.lastModified());
      return true;
    }

    private static Document parse(FetchResult.Page fetched) {
      try {
        return Jsoup.parse(new ByteArrayInputStream(fetched.body()), charsetOf(fetched.contentType()), fetched.finalUrl());
      } catch (IOException | UncheckedIOException e) {
        System.err.println("html: could not parse " + fetched.finalUrl());
        return null;
      }
    }

    /** The declared charset, or null to let the parser detect one. */
    private static String charsetOf(String contentType) {
      if (contentType == null) {
        return null;
      }
      Matcher matcher = CHARSET.matcher(contentType);
      if (!matcher.find()) {
        return null;
      }
      try {
        return Charset.isSupported(matcher.group(1)) ? matcher.group(1) : null;
      } catch (IllegalCharsetNameException e) {
        return null;
      }
    }

    private static String directives(Document document, FetchResult.Page fetched) {
      Element meta = document.selectFirst("meta[name=robots]");
      return ((meta == null ? "" : meta.attr("content")) + " " + (fetched.robotsHeader() == null ? "" : fetched.robotsHeader()))
        .toLowerCase(Locale.ROOT);
    }

    private static String title(Document document, String url) {
      String title = document.title().strip();
      if (title.isEmpty()) {
        Element heading = document.selectFirst("h1");
        title = heading == null || heading.text().isBlank() ? url : heading.text().strip();
      }
      return title.length() > ProtocolLimits.MAX_TITLE_CHARS ? title.substring(0, ProtocolLimits.MAX_TITLE_CHARS) : title;
    }

    private static Instant updatedAt(FetchResult.Page fetched, Document document) {
      Instant now = Instant.now();
      Instant stated = headerDate(fetched.lastModified());
      if (stated == null) {
        Element meta = document.selectFirst("meta[property=article:modified_time]");
        stated = meta == null ? null : metaDate(meta.attr("content"));
      }
      return stated == null || stated.isAfter(now) ? now : stated;
    }

    private static Instant headerDate(String value) {
      if (value == null) {
        return null;
      }
      try {
        return ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      } catch (DateTimeParseException e) {
        return null;
      }
    }

    private static Instant metaDate(String value) {
      try {
        return OffsetDateTime.parse(value.strip()).toInstant();
      } catch (DateTimeParseException e) {
        return null;
      }
    }

    private void afterPage() {
      processed++;
      long done = pages.values().stream().filter(page -> page.status != CrawlCursor.Status.QUEUED).count();
      sink.emit(new HarvestRecord.Progress(done, (long) pages.size()));
      if (processed % CHECKPOINT_EVERY == 0) {
        checkpoint();
      }
    }

    private void checkpoint() {
      sink.emit(new HarvestRecord.Checkpoint(cursor.toJson()));
    }

    private void finish() throws HarvesterException {
      boolean anyReported = pages.values().stream().anyMatch(page -> page.status == CrawlCursor.Status.REPORTED);
      cursor.active(false);
      checkpoint();
      if (!anyReported) {
        throw new HarvesterException(ErrorClass.TRANSIENT, "no page could be fetched" + (firstProblem == null ? "" : ": " + firstProblem));
      }
      if (!cursor.incomplete()) {
        sink.emit(new HarvestRecord.Sweep());
      }
    }
  }
}
