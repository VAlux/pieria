package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

import static dev.alvo.pieria.harvesters.teams.TeamsJson.message;
import static org.assertj.core.api.Assertions.assertThat;

class ChannelThreadsTests {

  private static final Instant T = Instant.parse("2026-11-14T09:00:00Z");

  @Test
  void postThenReplies() {
    ObjectNode post = message("100", "Ada", T, "<p>The API is down</p>").put("subject", "Outage")
      .put("webUrl", "https://teams.microsoft.com/l/message/c/100");
    List<JsonNode> replies = List.of(message("102", "Bo", T.plusSeconds(120), "<p>Fixed now</p>"),
      message("101", "Cy", T.plusSeconds(60), "<p>Looking into it</p>"));

    HarvestRecord.ConversationThread thread = ChannelThreads.build("t1", "c1", "Eng", "Ops", post, replies);

    assertThat(ChannelThreads.threadId("t1", "c1", "100")).isEqualTo("channel:t1:c1:100");
    assertThat(thread.id()).isEqualTo("channel:t1:c1:100");
    assertThat(thread.title()).isEqualTo("Eng / Ops: Outage");
    assertThat(thread.turns()).extracting(HarvestRecord.Turn::id).containsExactly("100", "101", "102");
    assertThat(thread.url()).isEqualTo("https://teams.microsoft.com/l/message/c/100");
    assertThat(thread.metadata()).containsEntry("kind", "channel").containsEntry("team", "Eng").containsEntry("channel", "Ops");
  }

  @Test
  void trivialPostIsKeptTrivialReplyIsNot() {
    HarvestRecord.ConversationThread thread = ChannelThreads.build("t1", "c1", "Eng", "Ops", message("100", "Ada", T, "ok"),
      List.of(message("101", "Bo", T.plusSeconds(1), "<p>thanks</p>")));

    assertThat(thread.turns()).extracting(HarvestRecord.Turn::text).containsExactly("ok");
    assertThat(thread.url()).isEqualTo("https://teams.microsoft.com");
  }

  @Test
  void deletedPostIsNull() {
    ObjectNode post = message("100", "Ada", T, "<p>x</p>").put("deletedDateTime", T.plusSeconds(5).toString());

    assertThat(ChannelThreads.build("t1", "c1", "Eng", "Ops", post, List.of(message("101", "Bo", T.plusSeconds(1), "<p>reply text</p>")))).isNull();
  }

  @Test
  void titleFromFirstLineWithoutSubject() {
    String longLine = "a".repeat(100);
    ObjectNode post = message("100", "Ada", T, "<p>" + longLine + "</p><p>second line</p>");

    assertThat(ChannelThreads.build("t1", "c1", "Eng", "Ops", post, List.of()).title()).isEqualTo("Eng / Ops: " + "a".repeat(80));
  }

  @Test
  void botAuthorComesFromTheApplication() {
    ObjectNode post = message("100", "Ada", T, "<p>Build failed</p>");
    post.putObject("from").putNull("user");
    ((ObjectNode) post.get("from")).putObject("application").put("displayName", "CI Bot");

    assertThat(ChannelThreads.build("t1", "c1", "Eng", "Ops", post, List.of()).turns().getFirst().author()).isEqualTo("CI Bot");
  }
}
