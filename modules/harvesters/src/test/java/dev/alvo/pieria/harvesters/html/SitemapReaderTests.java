package dev.alvo.pieria.harvesters.html;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SitemapReaderTests {

  private final StubSite site = StubSite.start();
  private final SitemapReader reader = new SitemapReader(new PageFetcher(_ -> { }, 0, Duration.ofSeconds(10)));

  @AfterEach
  void tearDown() {
    site.close();
  }

  private String urlSet(String... paths) {
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
    for (String path : paths) {
      xml.append("<url><loc>").append(site.baseUrl()).append(path).append("</loc></url>");
    }
    return xml.append("</urlset>").toString();
  }

  private void xml(String path, String body) {
    site.respond(path, 200, Map.of("Content-Type", "application/xml"), body);
  }

  private SitemapReader.Result read(String path, int max) {
    return reader.read(site.baseUrl() + path, max);
  }

  @Test
  void readsAUrlSet() {
    xml("/s.xml", urlSet("/a", "/b"));

    SitemapReader.Result result = read("/s.xml", 10);

    assertThat(result.urls()).containsExactly(site.baseUrl() + "/a", site.baseUrl() + "/b");
    assertThat(result.complete()).isTrue();
  }

  @Test
  void dropsInvalidUrls() {
    xml("/s.xml", "<urlset><url><loc>ftp://x/a</loc></url></urlset>");

    assertThat(read("/s.xml", 10).urls()).isEmpty();
  }

  @Test
  void followsASitemapIndex() {
    xml("/index.xml", "<sitemapindex><sitemap><loc>" + site.baseUrl() + "/s.xml</loc></sitemap></sitemapindex>");
    xml("/s.xml", urlSet("/a"));

    SitemapReader.Result result = read("/index.xml", 10);

    assertThat(result.urls()).containsExactly(site.baseUrl() + "/a");
    assertThat(result.complete()).isTrue();
  }

  @Test
  void refusesIndexesNestedTooDeep() {
    for (int i = 0; i < 4; i++) {
      xml("/i" + i + ".xml", "<sitemapindex><sitemap><loc>" + site.baseUrl() + "/i" + (i + 1) + ".xml</loc></sitemap></sitemapindex>");
    }

    assertThat(read("/i0.xml", 10).complete()).isFalse();
    assertThat(site.hits("/i3.xml")).isZero();
  }

  @Test
  void readsGzip() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write(urlSet("/a").getBytes(StandardCharsets.UTF_8));
    }
    site.respond("/s.xml.gz", 200, Map.of("Content-Type", "application/gzip"), bytes.toByteArray());

    assertThat(read("/s.xml.gz", 10).urls()).containsExactly(site.baseUrl() + "/a");
  }

  @Test
  void aFailedChildIsIncomplete() {
    xml("/index.xml", "<sitemapindex><sitemap><loc>" + site.baseUrl() + "/gone.xml</loc></sitemap></sitemapindex>");

    assertThat(read("/index.xml", 10).complete()).isFalse();
  }

  @Test
  void stopsAtTheLimitAndSaysSo() {
    xml("/s.xml", urlSet("/a", "/b", "/c"));

    SitemapReader.Result result = read("/s.xml", 2);

    assertThat(result.urls()).hasSize(2);
    assertThat(result.complete()).isFalse();
  }

  @Test
  void exactlyTheLimitIsComplete() {
    xml("/s.xml", urlSet("/a", "/b"));

    assertThat(read("/s.xml", 2).complete()).isTrue();
  }

  @Test
  void malformedXmlIsIncomplete() {
    xml("/s.xml", "this is not a sitemap");

    assertThat(read("/s.xml", 10).complete()).isFalse();
  }
}
