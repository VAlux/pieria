package dev.alvo.pieria.harvesters.html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Reads the page URLs out of a sitemap or sitemap index. */
public final class SitemapReader {

  private static final int MAX_INDEX_DEPTH = 2;
  private static final int MAX_UNPACKED_BYTES = 50 * 1024 * 1024;

  private final PageFetcher fetcher;

  public SitemapReader(PageFetcher fetcher) {
    this.fetcher = fetcher;
  }

  /** @param complete false when part of the sitemap couldn't be read, or it held more than {@code maxUrls} */
  public record Result(List<String> urls, boolean complete) {
  }

  public Result read(String sitemapUrl, int maxUrls) {
    Set<String> urls = new LinkedHashSet<>();
    boolean complete = visit(sitemapUrl, maxUrls, urls, 0);
    return new Result(List.copyOf(urls), complete);
  }

  /** Returns whether everything under {@code url} was read. */
  private boolean visit(String url, int maxUrls, Set<String> urls, int depth) {
    if (!(fetcher.fetchAny(url) instanceof FetchResult.Page page)) {
      return false;
    }
    Document xml;
    try {
      xml = Jsoup.parse(new String(unpacked(page.body()), StandardCharsets.UTF_8), "", Parser.xmlParser());
    } catch (IOException e) {
      return false;
    }
    if (xml.selectFirst("sitemapindex") != null) {
      return depth < MAX_INDEX_DEPTH && visitChildren(xml, maxUrls, urls, depth);
    }
    return xml.selectFirst("urlset") != null && collect(xml, maxUrls, urls);
  }

  private boolean visitChildren(Document index, int maxUrls, Set<String> urls, int depth) {
    boolean complete = true;
    for (Element location : index.select("sitemap > loc")) {
      String child = CrawlScope.normalize(location.text());
      complete &= child != null && visit(child, maxUrls, urls, depth + 1);
    }
    return complete;
  }

  private static boolean collect(Document urlSet, int maxUrls, Set<String> urls) {
    for (Element location : urlSet.select("url > loc")) {
      String url = CrawlScope.normalize(location.text());
      if (url == null || urls.contains(url)) {
        continue;
      }
      if (urls.size() >= maxUrls) {
        return false;
      }
      urls.add(url);
    }
    return true;
  }

  private static byte[] unpacked(byte[] body) throws IOException {
    boolean gzip = body.length > 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B;
    if (!gzip) {
      return body;
    }
    try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
      byte[] unpacked = in.readNBytes(MAX_UNPACKED_BYTES + 1);
      if (unpacked.length > MAX_UNPACKED_BYTES) {
        throw new IOException("sitemap is too large");
      }
      return unpacked;
    }
  }
}
