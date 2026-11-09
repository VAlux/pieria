package dev.alvo.pieria.harvesters.html;

import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CrawlCursorTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void roundTrips() {
    CrawlCursor cursor = CrawlCursor.read(null);
    cursor.active(true);
    cursor.markIncomplete();
    CrawlCursor.Page page = new CrawlCursor.Page(2);
    page.status = CrawlCursor.Status.REPORTED;
    page.validators("\"v1\"", "Wed, 21 Oct 2026 07:28:00 GMT");
    cursor.pages().put("https://x.test/a", page);
    cursor.pages().put("https://x.test/b", new CrawlCursor.Page(1));

    CrawlCursor read = CrawlCursor.read(cursor.toJson());

    assertThat(read.active()).isTrue();
    assertThat(read.incomplete()).isTrue();
    assertThat(read.pages().keySet()).containsExactly("https://x.test/a", "https://x.test/b");
    CrawlCursor.Page back = read.pages().get("https://x.test/a");
    assertThat(back.depth).isEqualTo(2);
    assertThat(back.status).isEqualTo(CrawlCursor.Status.REPORTED);
    assertThat(back.etag).isEqualTo("\"v1\"");
    assertThat(back.lastModified).isEqualTo("Wed, 21 Oct 2026 07:28:00 GMT");
    assertThat(read.pages().get("https://x.test/b").status).isEqualTo(CrawlCursor.Status.QUEUED);
  }

  @Test
  void nullOrUnknownVersionIsEmpty() {
    assertThat(CrawlCursor.read(null).pages()).isEmpty();
    assertThat(CrawlCursor.read(MAPPER.createObjectNode().put("v", 99)).pages()).isEmpty();
    assertThat(CrawlCursor.read(MAPPER.createObjectNode().put("other", true)).active()).isFalse();
  }

  @Test
  void malformedEntriesAreDropped() {
    JsonNode cursor = MAPPER.readTree("{\"v\":1,\"pages\":{\"https://x.test/a\":\"nope\",\"https://x.test/b\":[1,1,null,null]}}");

    assertThat(CrawlCursor.read(cursor).pages().keySet()).containsExactly("https://x.test/b");
  }

  @Test
  void aFullSiteOfLongUrlsFitsTheProtocol() {
    CrawlCursor cursor = CrawlCursor.read(null);
    for (int i = 0; i < 2000; i++) {
      String prefix = "https://x.test/" + i + "/";
      CrawlCursor.Page page = new CrawlCursor.Page(50);
      page.validators("e".repeat(128), "Wed, 21 Oct 2026 07:28:00 GMT");
      cursor.pages().put(prefix + "a".repeat(400 - prefix.length()), page);
    }

    JsonNode json = cursor.toJson();

    assertThat(json.toString().length()).isLessThanOrEqualTo(850_000);
    assertThat(new HarvestRecord.Checkpoint(json).cursor()).isEqualTo(json);
    assertThat(CrawlCursor.read(json).pages()).hasSize(2000);
  }

  @Test
  void aSmallSiteKeepsItsValidators() {
    CrawlCursor cursor = CrawlCursor.read(null);
    CrawlCursor.Page page = new CrawlCursor.Page(0);
    page.validators("\"v\"", null);
    cursor.pages().put("https://x.test/", page);

    assertThat(CrawlCursor.read(cursor.toJson()).pages().get("https://x.test/").etag).isEqualTo("\"v\"");
  }

  @Test
  void dropsAnOverlongEtag() {
    CrawlCursor.Page page = new CrawlCursor.Page(0);
    page.validators("e".repeat(129), "x");

    assertThat(page.etag).isNull();
    assertThat(page.lastModified).isEqualTo("x");
  }

  @Test
  void dropsANonAsciiEtag() {
    CrawlCursor.Page page = new CrawlCursor.Page(0);
    page.validators("café", null);

    assertThat(page.etag).isNull();
  }
}
