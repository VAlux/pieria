package dev.alvo.pieria.harvesters.html;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Everything a crawl remembers between runs: the known pages and how far the current crawl has got. */
final class CrawlCursor {

  /** Below the protocol's cursor limit, leaving room for the config in the envelope. */
  private static final int MAX_CHARS = 850_000;
  private static final int MAX_ETAG_CHARS = 128;
  private static final int VERSION = 1;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  enum Status {
    QUEUED, REPORTED, FAILED
  }

  /** One known page. Mutable because a crawl updates it as it goes. */
  static final class Page {
    int depth;
    Status status = Status.QUEUED;
    String etag;
    String lastModified;

    Page(int depth) {
      this.depth = depth;
    }

    void validators(String newEtag, String newLastModified) {
      etag = storable(newEtag);
      lastModified = storable(newLastModified);
    }

    private static String storable(String value) {
      boolean fits = value != null && value.length() <= MAX_ETAG_CHARS
        && StandardCharsets.US_ASCII.newEncoder().canEncode(value);
      return fits ? value : null;
    }
  }

  private final Map<String, Page> pages = new LinkedHashMap<>();
  private boolean active;
  private boolean incomplete;

  private CrawlCursor() {
  }

  /** The cursor from an earlier run. Anything unreadable starts afresh, which only costs extra requests. */
  static CrawlCursor read(JsonNode json) {
    CrawlCursor cursor = new CrawlCursor();
    if (json == null || json.path("v").asInt(0) != VERSION) {
      return cursor;
    }
    cursor.active = json.path("active").asBoolean(false);
    cursor.incomplete = json.path("incomplete").asBoolean(false);
    for (Map.Entry<String, JsonNode> entry : json.path("pages").properties()) {
      JsonNode fields = entry.getValue();
      if (fields.isArray() && fields.size() == 4) {
        Page page = new Page(fields.get(0).asInt(0));
        page.status = Status.values()[Math.clamp(fields.get(1).asInt(0), 0, Status.values().length - 1)];
        page.etag = fields.get(2).isString() ? fields.get(2).asString() : null;
        page.lastModified = fields.get(3).isString() ? fields.get(3).asString() : null;
        cursor.pages.put(entry.getKey(), page);
      }
    }
    return cursor;
  }

  /** Validators are only an optimization, so they are what gives way on a site too large to fit. */
  JsonNode toJson() {
    ObjectNode full = encode(true);
    return full.toString().length() <= MAX_CHARS ? full : encode(false);
  }

  private ObjectNode encode(boolean withValidators) {
    ObjectNode json = MAPPER.createObjectNode().put("v", VERSION).put("active", active).put("incomplete", incomplete);
    ObjectNode encodedPages = json.putObject("pages");
    pages.forEach((url, page) -> {
      ArrayNode fields = encodedPages.putArray(url).add(page.depth).add(page.status.ordinal());
      fields.add(withValidators ? page.etag : null).add(withValidators ? page.lastModified : null);
    });
    return json;
  }

  Map<String, Page> pages() {
    return pages;
  }

  boolean active() {
    return active;
  }

  void active(boolean value) {
    active = value;
  }

  boolean incomplete() {
    return incomplete;
  }

  void markIncomplete() {
    incomplete = true;
  }

  /** Starts a new crawl over the known pages, keeping their validators. */
  void restart() {
    incomplete = false;
    pages.values().forEach(page -> page.status = Status.QUEUED);
  }
}
