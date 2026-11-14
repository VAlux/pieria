package dev.alvo.pieria.harvesters.confluence;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvesters.JsonStub;
import dev.alvo.pieria.harvesters.atlassian.AtlassianClient;
import dev.alvo.pieria.harvesters.incremental.Change;
import dev.alvo.pieria.harvesters.incremental.Page;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfluenceFeedTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

  static ObjectNode config(StubConfluence confluence) {
    ObjectNode config = MAPPER.createObjectNode().put("baseUrl", confluence.baseUrl())
      .put("deployment", confluence.cloud ? "cloud" : "datacenter");
    config.putArray("spaces").add("OPS");
    if (confluence.cloud) {
      config.put("email", StubConfluence.EMAIL);
    }
    return config;
  }

  private static ConfluenceFeed feed(StubConfluence confluence, ObjectNode config) throws HarvesterException {
    ConfluenceConfig parsed = ConfluenceConfig.parse(new HarvestRequest(1, config, Map.of("token", StubConfluence.TOKEN), null, null));
    return new ConfluenceFeed(parsed, new AtlassianClient(parsed.site(), Duration.ofSeconds(10)));
  }

  private static Set<String> allLiveIds(ConfluenceFeed feed) throws HarvesterException {
    Set<String> ids = new LinkedHashSet<>();
    String token = null;
    do {
      Page<String> page = feed.liveIds(token);
      ids.addAll(page.entries());
      token = page.next();
    } while (token != null);
    return ids;
  }

  private static void addPages(StubConfluence confluence, int count) {
    for (int i = 1; i <= count; i++) {
      confluence.add(String.valueOf(100 + i), "OPS", "Page " + i, T.plusSeconds(i), "<p>body " + i + "</p>");
    }
  }

  @Test
  void cqlCombinesSpacesAndFilter() throws HarvesterException {
    try (StubConfluence confluence = new StubConfluence(false)) {
      ObjectNode config = config(confluence).put("cql", "label = runbook OR label = howto");
      config.putArray("spaces").add("OPS").add("DEV");

      feed(confluence, config).changes(Instant.now().minusSeconds(61), null);

      assertThat(confluence.requests(confluence.searchPath()).getFirst().query().get("cql")).isEqualTo(
        "type in (page, blogpost) AND space in (\"OPS\",\"DEV\") AND (label = runbook OR label = howto)"
          + " AND lastmodified >= now(\"-2m\") order by lastmodified asc");
    }
  }

  @Test
  void cloudFollowsNextLinksUnderTheWikiContext() throws HarvesterException {
    try (StubConfluence confluence = new StubConfluence(true)) {
      addPages(confluence, 130);
      ConfluenceFeed feed = feed(confluence, config(confluence));
      feed.begin();

      Page<Change<JsonNode>> first = feed.changes(null, null);
      Page<Change<JsonNode>> second = feed.changes(null, first.next());

      assertThat(first.entries()).hasSize(100);
      assertThat(second.entries()).hasSize(30);
      assertThat(second.next()).isNull();
      assertThat(confluence.requests("/wiki/rest/api/content/search").get(1).query()).containsEntry("cursor", "c100")
        .containsEntry("expand", "version");
    }
  }

  @Test
  void dataCenterStepsBackAndChecksTheOverlap() throws HarvesterException {
    try (StubConfluence confluence = new StubConfluence(false)) {
      addPages(confluence, 130);
      ConfluenceFeed feed = feed(confluence, config(confluence));

      Page<Change<JsonNode>> first = feed.changes(null, null);
      Page<Change<JsonNode>> second = feed.changes(null, first.next());

      assertThat(first.next()).isEqualTo("90:200");
      assertThat(second.entries()).hasSize(40);
      assertThat(second.next()).isNull();
      assertThat(confluence.requests("/rest/api/content/search").get(1).query()).containsEntry("start", "90");
    }
  }

  @Test
  void serverCapOnLimitIsNotTheLastPage() throws HarvesterException {
    try (StubConfluence confluence = new StubConfluence(false)) {
      addPages(confluence, 130);
      confluence.capLimit(50);

      assertThat(allLiveIds(feed(confluence, config(confluence)))).hasSize(130);
    }
  }

  @Test
  void deletionsPastTheOverlapDuringListingFailTransient() throws HarvesterException {
    try (StubConfluence confluence = new StubConfluence(false)) {
      addPages(confluence, 130);
      confluence.afterFirstSearchPage(() -> {
        for (int i = 1; i <= 15; i++) {
          confluence.delete(String.valueOf(100 + i));
        }
      });
      ConfluenceFeed feed = feed(confluence, config(confluence));

      assertThatThrownBy(() -> allLiveIds(feed)).isInstanceOfSatisfying(HarvesterException.class,
        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.TRANSIENT));
    }
  }

  @Test
  void missingBodyFailsTransient() throws HarvesterException {
    try (StubConfluence confluence = new StubConfluence(false)) {
      confluence.add("101", "OPS", "Runbook", T, "<p>Restart.</p>");
      confluence.dropBodies();
      ConfluenceFeed feed = feed(confluence, config(confluence));
      List<Change<JsonNode>> changes = feed.changes(null, null).entries();

      assertThatThrownBy(() -> feed.load(changes)).isInstanceOfSatisfying(HarvesterException.class, e -> {
        assertThat(e.errorClass()).isEqualTo(ErrorClass.TRANSIENT);
        assertThat(e.getMessage()).contains("without its body");
      });
    }
  }

  @Test
  void anonymousSessionIsAuth() throws HarvesterException {
    try (StubConfluence confluence = new StubConfluence(false)) {
      confluence.anonymous();
      ConfluenceFeed feed = feed(confluence, config(confluence));

      assertThatThrownBy(feed::begin).isInstanceOfSatisfying(HarvesterException.class,
        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.AUTH));
    }
  }

  @Test
  void deletedPageIsLeftOutOfALoad() throws HarvesterException {
    try (StubConfluence confluence = new StubConfluence(true)) {
      addPages(confluence, 3);
      ConfluenceFeed feed = feed(confluence, config(confluence));
      List<Change<JsonNode>> changes = feed.changes(null, null).entries();
      confluence.delete("102");

      assertThat(feed.load(changes)).extracting(HarvestRecord.Item::id).containsExactlyInAnyOrder("101", "103");
      assertThat(confluence.requests("/wiki/rest/api/content/search").getLast().query().get("cql")).isEqualTo("id in (101,102,103)");
    }
  }

  @Test
  void liveIdsListEveryPageByCreated() throws HarvesterException {
    try (StubConfluence confluence = new StubConfluence(true)) {
      addPages(confluence, 3);

      Page<String> ids = feed(confluence, config(confluence)).liveIds(null);

      assertThat(ids.entries()).containsExactly("101", "102", "103");
      JsonStub.Request request = confluence.requests("/wiki/rest/api/content/search").getFirst();
      assertThat(request.query().get("cql")).isEqualTo("type in (page, blogpost) AND space in (\"OPS\") order by created asc");
      assertThat(request.query()).containsEntry("limit", "100").doesNotContainKey("expand");
    }
  }

  @Test
  void nextLinkOutsideBaseUrlIsTransient() throws HarvesterException {
    try (StubConfluence confluence = new StubConfluence(true)) {
      addPages(confluence, 130);
      confluence.foreignNext("https://evil.example.com/rest/api/content/search?cursor=c25");
      ConfluenceFeed feed = feed(confluence, config(confluence));

      assertThatThrownBy(() -> feed.changes(null, null)).isInstanceOfSatisfying(HarvesterException.class, e -> {
        assertThat(e.errorClass()).isEqualTo(ErrorClass.TRANSIENT);
        assertThat(e.getMessage()).contains("confluence returned a next link outside baseUrl");
      });
    }
  }

  @Test
  void lookbackBecomesRelativeMinutes() throws HarvesterException {
    try (StubConfluence confluence = new StubConfluence(false)) {
      feed(confluence, config(confluence)).changes(Instant.now().minus(Duration.ofMinutes(10)), null);

      assertThat(confluence.requests("/rest/api/content/search").getFirst().query().get("cql"))
        .contains("lastmodified >= now(\"-10m\")");
    }
  }

  @Test
  void documentUsesVersionAuthorAndWebUi() throws HarvesterException {
    try (StubConfluence confluence = new StubConfluence(true)) {
      StubConfluence.Content page = confluence.add("101", "OPS", "Runbook", T, "<h1>Restart</h1><p>Do it.</p>");
      page.edit(T.plusSeconds(60), "<h1>Restart</h1><p>Do it twice.</p>");
      ConfluenceFeed feed = feed(confluence, config(confluence));

      Change<JsonNode> change = feed.changes(null, null).entries().getFirst();
      HarvestRecord.Document document = (HarvestRecord.Document) feed.load(List.of(change)).getFirst();

      assertThat(change.id()).isEqualTo("101");
      assertThat(change.version()).isEqualTo("2");
      assertThat(change.changedAt()).isEqualTo(T.plusSeconds(60));
      assertThat(document.id()).isEqualTo("101");
      assertThat(document.version()).isEqualTo("2");
      assertThat(document.title()).isEqualTo("Runbook");
      assertThat(document.author()).isEqualTo("Ada");
      assertThat(document.updatedAt()).isEqualTo(T.plusSeconds(60));
      assertThat(document.url()).isEqualTo(confluence.baseUrl() + "/wiki/spaces/OPS/pages/101");
      assertThat(document.text()).contains("# Restart").contains("Do it twice.");
      assertThat(document.metadata()).containsEntry("space", "OPS").containsEntry("type", "page");
    }
  }

  @Test
  void blankPageStaysLive() throws HarvesterException {
    try (StubConfluence confluence = new StubConfluence(false)) {
      confluence.add("101", "OPS", "Empty page", T, "");
      ConfluenceFeed feed = feed(confluence, config(confluence));

      HarvestRecord.Document document = (HarvestRecord.Document) feed.load(feed.changes(null, null).entries()).getFirst();

      assertThat(document.text()).isEqualTo("Empty page");
    }
  }
}
