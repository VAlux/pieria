package dev.alvo.pieria.harvesters.jira;

import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IssueThreadsTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String BASE = "https://acme.atlassian.net";
  private static final Instant CREATED = Instant.parse("2026-10-01T10:00:00Z");

  private static ObjectNode issue() {
    ObjectNode issue = MAPPER.createObjectNode().put("id", "10001").put("key", "ACME-12");
    ObjectNode fields = issue.putObject("fields");
    fields.put("summary", "Login fails on Safari");
    fields.put("created", "2026-10-01T10:00:00.000+0000");
    fields.put("updated", "2026-10-02T10:00:00.000+0000");
    fields.putObject("status").put("name", "Done");
    fields.putObject("issuetype").put("name", "Bug");
    fields.putObject("project").put("key", "ACME");
    fields.putObject("reporter").put("displayName", "Ada");
    fields.putArray("labels").add("web").add("auth");
    issue.putObject("renderedFields").put("description", "<h2>Steps</h2><p>Open the <b>login</b> page.</p>");
    return issue;
  }

  private static JsonNode comment(int id, String author, String created, String html) {
    ObjectNode comment = MAPPER.createObjectNode().put("id", String.valueOf(id)).put("created", created).put("renderedBody", html);
    comment.putObject("author").put("displayName", author);
    return comment;
  }

  @Test
  void descriptionTurnCarriesTheHeaderAndMarkdown() {
    HarvestRecord.ConversationThread thread = IssueThreads.build(BASE, issue(), List.of());

    assertThat(thread.id()).isEqualTo("10001");
    assertThat(thread.title()).isEqualTo("ACME-12: Login fails on Safari");
    assertThat(thread.url()).isEqualTo(BASE + "/browse/ACME-12");
    assertThat(thread.metadata()).containsEntry("key", "ACME-12").containsEntry("project", "ACME").containsEntry("status", "Done")
      .containsEntry("type", "Bug");
    HarvestRecord.Turn description = thread.turns().getFirst();
    assertThat(description.id()).isEqualTo("description");
    assertThat(description.author()).isEqualTo("Ada");
    assertThat(description.timestamp()).isEqualTo(CREATED);
    assertThat(description.text()).startsWith("Type: Bug · Status: Done · Labels: web, auth\n\n");
    assertThat(description.text()).contains("## Steps").contains("**login**");
  }

  @Test
  void commentsAreOrderedByCreated() {
    List<JsonNode> comments = List.of(
      comment(3, "Cy", "2026-10-03T10:00:00.000+0000", "<p>third</p>"),
      comment(2, "Bo", "2026-10-02T10:00:00.000+0000", "<p>second</p>"));

    HarvestRecord.ConversationThread thread = IssueThreads.build(BASE, issue(), comments);

    assertThat(thread.turns()).extracting(HarvestRecord.Turn::id).containsExactly("description", "comment:2", "comment:3");
    assertThat(thread.turns().get(1).author()).isEqualTo("Bo");
    assertThat(thread.turns().get(1).text()).isEqualTo("second");
    assertThat(thread.turns().get(1).timestamp()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
  }

  @Test
  void newCommentOnlyAppendsATurn() {
    List<JsonNode> before = List.of(comment(2, "Bo", "2026-10-02T10:00:00.000+0000", "<p>second</p>"));
    List<JsonNode> after = new ArrayList<>(before);
    after.add(comment(3, "Cy", "2026-10-03T10:00:00.000+0000", "<p>third</p>"));

    List<HarvestRecord.Turn> first = IssueThreads.build(BASE, issue(), before).turns();
    List<HarvestRecord.Turn> second = IssueThreads.build(BASE, issue(), after).turns();

    assertThat(second.subList(0, first.size())).isEqualTo(first);
    assertThat(second).hasSize(first.size() + 1);
  }

  @Test
  void emptyFieldsAreLeftOutOfTheHeader() {
    ObjectNode issue = issue();
    ((ObjectNode) issue.get("fields")).remove("labels");
    ((ObjectNode) issue.get("fields")).putNull("priority");
    ((ObjectNode) issue.get("fields")).putArray("components");

    String text = IssueThreads.build(BASE, issue, List.of()).turns().getFirst().text();

    assertThat(text).startsWith("Type: Bug · Status: Done\n\n").doesNotContain("Priority").doesNotContain("Components");
  }

  @Test
  void adfDescriptionWithoutRenderedHtmlIsIgnored() {
    ObjectNode issue = issue();
    issue.remove("renderedFields");
    ((ObjectNode) issue.get("fields")).putObject("description").put("type", "doc").put("version", 1);

    assertThat(IssueThreads.build(BASE, issue, List.of()).turns().getFirst().text()).isEqualTo("Type: Bug · Status: Done · Labels: web, auth");
  }

  @Test
  void wikiMarkupDescriptionIsUsedWhenNotRendered() {
    ObjectNode issue = issue();
    issue.remove("renderedFields");
    ((ObjectNode) issue.get("fields")).put("description", "h2. Steps\nOpen *login*");

    assertThat(IssueThreads.build(BASE, issue, List.of()).turns().getFirst().text()).endsWith("\n\nh2. Steps\nOpen *login*");
  }

  @Test
  void blankCommentsAreSkipped() {
    List<JsonNode> comments = List.of(comment(2, "Bo", "2026-10-02T10:00:00.000+0000", "<p> </p>"));

    assertThat(IssueThreads.build(BASE, issue(), comments).turns()).hasSize(1);
  }

  @Test
  void tooManyCommentsKeepsTheOldest() {
    List<JsonNode> comments = new ArrayList<>();
    for (int i = 1; i <= ProtocolLimits.MAX_TURNS + 5; i++) {
      comments.add(comment(i, "Bo", "2026-10-02T10:00:00.000+0000", "<p>c" + i + "</p>"));
    }

    HarvestRecord.ConversationThread thread = IssueThreads.build(BASE, issue(), comments);

    assertThat(thread.turns()).hasSize(ProtocolLimits.MAX_TURNS);
    assertThat(thread.turns().getLast().id()).isEqualTo("comment:" + (ProtocolLimits.MAX_TURNS - 1));
  }

  @Test
  void hugeTextStaysWithinTheLimit() {
    String big = "<p>" + "x".repeat(ProtocolLimits.MAX_TEXT_CHARS - 100) + "</p>";
    List<JsonNode> comments = List.of(
      comment(2, "Bo", "2026-10-02T10:00:00.000+0000", big),
      comment(3, "Cy", "2026-10-03T10:00:00.000+0000", big));

    HarvestRecord.ConversationThread thread = IssueThreads.build(BASE, issue(), comments);

    long total = thread.turns().stream().mapToLong(turn -> turn.text().length()).sum();
    assertThat(total).isLessThanOrEqualTo(ProtocolLimits.MAX_TEXT_CHARS);
    assertThat(thread.turns()).extracting(HarvestRecord.Turn::id).startsWith("description", "comment:2");
    assertThat(thread.turns().get(1).text()).hasSize(ProtocolLimits.MAX_TEXT_CHARS - 100);
  }

  @Test
  void missingReporterFallsBackToCreatorThenUnknown() {
    ObjectNode issue = issue();
    ObjectNode fields = (ObjectNode) issue.get("fields");
    fields.remove("reporter");
    fields.putObject("creator").put("displayName", "Grace");
    assertThat(IssueThreads.build(BASE, issue, List.of()).turns().getFirst().author()).isEqualTo("Grace");

    fields.remove("creator");
    assertThat(IssueThreads.build(BASE, issue, List.of()).turns().getFirst().author()).isEqualTo("unknown");
  }

  @Test
  void parsesJiraTimestamps() {
    assertThat(IssueThreads.time("2026-10-01T12:30:00.000+0200")).isEqualTo(Instant.parse("2026-10-01T10:30:00Z"));
    assertThat(IssueThreads.time("2026-10-01T10:30:00.000+02:00")).isEqualTo(Instant.parse("2026-10-01T08:30:00Z"));
    assertThat(IssueThreads.time("garbage")).isNull();
    assertThat(IssueThreads.time(null)).isNull();
  }
}
