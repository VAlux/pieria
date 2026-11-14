package dev.alvo.pieria.harvesters.jira;

import dev.alvo.pieria.harvester.HarvesterException;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
      jira.add(String.valueOf(10_000 + i), "T-" + i, "Issue " + i, T.plusSeconds(i), T.plusSeconds(1000 + i), "<p>body " + i + "</p>");
    }
  }

  @Test
  void cloudUsesSearchJqlAndNextPageToken() throws HarvesterException {
    try (StubJira jira = new StubJira(true)) {
      addIssues(jira, 60);
      JiraFeed feed = feed(jira, "project = T");
      feed.begin();

      Page<Change<JsonNode>> first = feed.changes(null, null);
      Page<Change<JsonNode>> second = feed.changes(null, first.next());

      assertThat(first.entries()).hasSize(50);
      assertThat(first.next()).isEqualTo("50");
      assertThat(second.entries()).hasSize(10);
      assertThat(second.next()).isNull();
      JsonStub.Request request = jira.requests("/rest/api/3/search/jql").get(1);
      assertThat(request.query()).containsEntry("nextPageToken", "50").containsEntry("expand", "renderedFields");
      assertThat(request.query().get("fields")).isEqualTo(IssueThreads.FIELDS);
    }
  }

  @Test
  void dataCenterUsesSearchWithStartAtOverlap() throws HarvesterException {
    try (StubJira jira = new StubJira(false)) {
      addIssues(jira, 60);
      JiraFeed feed = feed(jira, "project = T");

      Page<Change<JsonNode>> first = feed.changes(null, null);
      Page<Change<JsonNode>> second = feed.changes(null, first.next());

      assertThat(first.next()).isEqualTo("40");
      assertThat(second.entries()).hasSize(20);
      assertThat(second.next()).isNull();
      assertThat(jira.requests("/rest/api/2/search").get(1).query()).containsEntry("startAt", "40");
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
  void lookbackBecomesRelativeMinutes() throws HarvesterException {
    try (StubJira jira = new StubJira(true)) {
      feed(jira, "project = T").changes(Duration.ofSeconds(61), null);

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
  void commentsArePagedThroughTotal() throws HarvesterException {
    try (StubJira jira = new StubJira(false)) {
      StubJira.Issue issue = jira.add("10001", "T-1", "One", T, T.plusSeconds(60), "<p>x</p>");
      for (int i = 1; i <= 150; i++) {
        issue.comment(String.valueOf(i), "Bo", T.plusSeconds(100 + i), "<p>c" + i + "</p>");
      }
      JiraFeed feed = feed(jira, "project = T");

      HarvestRecord.ConversationThread thread = (HarvestRecord.ConversationThread) feed.load(feed.changes(null, null).entries().getFirst());

      assertThat(thread.turns()).hasSize(151);
      List<JsonStub.Request> requests = jira.requests("/rest/api/2/issue/10001/comment");
      assertThat(requests).hasSize(2);
      assertThat(requests.getFirst().query()).containsEntry("expand", "renderedBody").containsEntry("orderBy", "created");
    }
  }

  @Test
  void issueDeletedBeforeLoadIsNull() throws HarvesterException {
    try (StubJira jira = new StubJira(true)) {
      jira.add("10001", "T-1", "One", T, T.plusSeconds(60), "<p>x</p>");
      JiraFeed feed = feed(jira, "project = T");
      Change<JsonNode> change = feed.changes(null, null).entries().getFirst();
      jira.delete("10001");

      assertThat(feed.load(change)).isNull();
    }
  }

  @Test
  void jqlIsWrappedSoOrBindsInside() throws HarvesterException {
    try (StubJira jira = new StubJira(true)) {
      feed(jira, "project = A OR project = B").changes(Duration.ofMinutes(10), null);

      assertThat(jira.requests("/rest/api/3/search/jql").getFirst().query().get("jql"))
        .startsWith("(project = A OR project = B) AND updated >= ");
    }
  }
}
