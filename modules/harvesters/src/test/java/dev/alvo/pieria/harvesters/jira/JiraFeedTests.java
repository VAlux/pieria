package dev.alvo.pieria.harvesters.jira;

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

class JiraFeedTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

  private static JiraFeed feed(StubJira jira, String jql) throws HarvesterException {
    ObjectNode config = MAPPER.createObjectNode().put("baseUrl", jira.baseUrl()).put("jql", jql)
      .put("deployment", jira.cloud ? "cloud" : "datacenter");
    if (jira.cloud) {
      config.put("email", StubJira.EMAIL);
    }
    JiraConfig parsed = JiraConfig.parse(new HarvestRequest(1, config, Map.of("token", StubJira.TOKEN), null, null));
    return new JiraFeed(parsed, new AtlassianClient(parsed.site(), Duration.ofSeconds(10)));
  }

  private static void addIssues(StubJira jira, int count) {
    for (int i = 1; i <= count; i++) {
      jira.add(String.valueOf(10_000 + i), "T-" + i, "Issue " + i, T.plusSeconds(i), T.plusSeconds(10_000 + i), "<p>body " + i + "</p>");
    }
  }

  private static Set<String> allLiveIds(JiraFeed feed) throws HarvesterException {
    Set<String> ids = new LinkedHashSet<>();
    String token = null;
    do {
      Page<String> page = feed.liveIds(token);
      ids.addAll(page.entries());
      token = page.next();
    } while (token != null);
    return ids;
  }

  @Test
  void cloudListsChangesLightlyWithNextPageToken() throws HarvesterException {
    try (StubJira jira = new StubJira(true)) {
      addIssues(jira, 1_200);
      JiraFeed feed = feed(jira, "project = T");
      feed.begin();

      Page<Change<JsonNode>> first = feed.changes(null, null);
      Page<Change<JsonNode>> second = feed.changes(null, first.next());

      assertThat(first.entries()).hasSize(1_000);
      assertThat(first.next()).isEqualTo("1000");
      assertThat(second.entries()).hasSize(200);
      assertThat(second.next()).isNull();
      JsonStub.Request request = jira.requests("/rest/api/3/search/jql").get(1);
      assertThat(request.query()).containsEntry("nextPageToken", "1000").containsEntry("fields", "updated")
        .doesNotContainKey("expand");
    }
  }

  @Test
  void dataCenterStepsBackAndChecksTheOverlap() throws HarvesterException {
    try (StubJira jira = new StubJira(false)) {
      addIssues(jira, 1_200);
      JiraFeed feed = feed(jira, "project = T");

      Page<Change<JsonNode>> first = feed.changes(null, null);
      Page<Change<JsonNode>> second = feed.changes(null, first.next());

      assertThat(first.next()).isEqualTo("990:11000");
      assertThat(second.entries()).hasSize(210);
      assertThat(second.next()).isNull();
      assertThat(jira.requests("/rest/api/2/search").get(1).query()).containsEntry("startAt", "990");
    }
  }

  @Test
  void serverCapOnPageSizeIsNotTheLastPage() throws HarvesterException {
    try (StubJira jira = new StubJira(false)) {
      addIssues(jira, 1_200);
      jira.capMaxResults(500);

      assertThat(allLiveIds(feed(jira, "project = T"))).hasSize(1_200);
    }
  }

  @Test
  void deletionsPastTheOverlapDuringListingFailTransient() throws HarvesterException {
    try (StubJira jira = new StubJira(false)) {
      addIssues(jira, 1_200);
      jira.afterFirstSearchPage(() -> {
        for (int i = 1; i <= 15; i++) {
          jira.delete(String.valueOf(10_000 + i));
        }
      });
      JiraFeed feed = feed(jira, "project = T");

      assertThatThrownBy(() -> allLiveIds(feed)).isInstanceOfSatisfying(HarvesterException.class, e -> {
        assertThat(e.errorClass()).isEqualTo(ErrorClass.TRANSIENT);
        assertThat(e.getMessage()).contains("shifted while paging");
      });
    }
  }

  @Test
  void fewDeletionsDuringListingAreAbsorbed() throws HarvesterException {
    try (StubJira jira = new StubJira(false)) {
      addIssues(jira, 1_200);
      jira.afterFirstSearchPage(() -> {
        for (int i = 1; i <= 3; i++) {
          jira.delete(String.valueOf(10_000 + i));
        }
      });

      Set<String> ids = allLiveIds(feed(jira, "project = T"));

      // The three were listed before they went; every issue still live is there.
      assertThat(ids).hasSize(1_200);
    }
  }

  @Test
  void cloudPageWithoutATokenThatIsNotLastFailsTransient() throws HarvesterException {
    try (StubJira jira = new StubJira(true)) {
      addIssues(jira, 1_200);
      jira.breakLastPage();
      JiraFeed feed = feed(jira, "project = T");

      assertThatThrownBy(() -> feed.liveIds(null)).isInstanceOfSatisfying(HarvesterException.class,
        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.TRANSIENT));
    }
  }

  @Test
  void changeCarriesIdVersionAndTime() throws HarvesterException {
    try (StubJira jira = new StubJira(true)) {
      jira.add("10001", "T-1", "One", T, T.plusSeconds(60), "<p>x</p>");

      Change<JsonNode> change = feed(jira, "project = T").changes(null, null).entries().getFirst();

      assertThat(change.id()).isEqualTo("10001");
      assertThat(change.changedAt()).isEqualTo(T.plusSeconds(60));
      assertThat(change.version()).isEqualTo(String.valueOf(T.plusSeconds(60).toEpochMilli()));
    }
  }

  @Test
  void boundIsMeasuredFromTheGivenInstant() throws HarvesterException {
    try (StubJira jira = new StubJira(true)) {
      feed(jira, "project = T").changes(Instant.now().minusSeconds(61), null);

      assertThat(jira.requests("/rest/api/3/search/jql").getFirst().query().get("jql"))
        .isEqualTo("(project = T) AND updated >= \"-2m\" ORDER BY updated ASC, key ASC");
    }
  }

  @Test
  void firstRunHasNoUpdatedClause() throws HarvesterException {
    try (StubJira jira = new StubJira(false)) {
      feed(jira, "project = T").changes(null, null);

      assertThat(jira.requests("/rest/api/2/search").getFirst().query().get("jql")).isEqualTo("(project = T) ORDER BY updated ASC, key ASC");
    }
  }

  @Test
  void liveIdsOrderByCreated() throws HarvesterException {
    try (StubJira jira = new StubJira(true)) {
      addIssues(jira, 3);

      Page<String> ids = feed(jira, "project = T").liveIds(null);

      assertThat(ids.entries()).containsExactly("10001", "10002", "10003");
      JsonStub.Request request = jira.requests("/rest/api/3/search/jql").getFirst();
      assertThat(request.query().get("jql")).isEqualTo("(project = T) ORDER BY created ASC, key ASC");
      assertThat(request.query()).containsEntry("fields", "created").containsEntry("maxResults", "1000").doesNotContainKey("expand");
    }
  }

  @Test
  void loadFetchesEachIssueRenderedWithItsComments() throws HarvesterException {
    try (StubJira jira = new StubJira(false)) {
      StubJira.Issue issue = jira.add("10001", "T-1", "One", T, T.plusSeconds(60), "<p>described</p>");
      for (int i = 1; i <= 150; i++) {
        issue.comment(String.valueOf(i), "Bo", T.plusSeconds(100 + i), "<p>c" + i + "</p>");
      }
      JiraFeed feed = feed(jira, "project = T");

      List<HarvestRecord.Item> items = feed.load(feed.changes(null, null).entries());

      HarvestRecord.ConversationThread thread = (HarvestRecord.ConversationThread) items.getFirst();
      assertThat(thread.turns()).hasSize(151);
      assertThat(thread.turns().getFirst().text()).endsWith("described");
      assertThat(jira.requests("/rest/api/2/issue/10001").getFirst().query()).containsEntry("expand", "renderedFields")
        .containsEntry("fields", IssueThreads.FIELDS);
      List<JsonStub.Request> comments = jira.requests("/rest/api/2/issue/10001/comment");
      assertThat(comments).hasSize(2);
      assertThat(comments.getFirst().query()).containsEntry("expand", "renderedBody").containsEntry("orderBy", "created");
    }
  }

  @Test
  void issueDeletedBeforeLoadIsLeftOut() throws HarvesterException {
    try (StubJira jira = new StubJira(true)) {
      jira.add("10001", "T-1", "One", T, T.plusSeconds(60), "<p>x</p>");
      jira.add("10002", "T-2", "Two", T, T.plusSeconds(61), "<p>y</p>");
      JiraFeed feed = feed(jira, "project = T");
      List<Change<JsonNode>> changes = feed.changes(null, null).entries();
      jira.delete("10001");

      assertThat(feed.load(changes)).extracting(HarvestRecord.Item::id).containsExactly("10002");
    }
  }

  @Test
  void jqlIsWrappedSoOrBindsInside() throws HarvesterException {
    try (StubJira jira = new StubJira(true)) {
      feed(jira, "project = A OR project = B").changes(Instant.now().minus(Duration.ofMinutes(10)), null);

      assertThat(jira.requests("/rest/api/3/search/jql").getFirst().query().get("jql"))
        .startsWith("(project = A OR project = B) AND updated >= ");
    }
  }
}
