package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static dev.alvo.pieria.harvesters.teams.TeamsJson.MAPPER;
import static dev.alvo.pieria.harvesters.teams.TeamsJson.message;
import static org.assertj.core.api.Assertions.assertThat;

class ChatWindowsTests {

  private static final LocalDate DAY = LocalDate.parse("2026-11-14");
  private static final Instant T = Instant.parse("2026-11-14T09:00:00Z");

  private static ObjectNode chat(String topic) {
    ObjectNode chat = MAPPER.createObjectNode().put("id", "19:abc@thread.v2").put("chatType", "group");
    if (topic != null) {
      chat.put("topic", topic);
    } else {
      chat.putNull("topic");
    }
    return chat;
  }

  @Test
  void lateEveningBelongsToTheLocalDay() {
    Instant late = Instant.parse("2026-11-14T22:30:00Z");

    assertThat(ChatWindows.day(late, ZoneId.of("Europe/Kyiv"))).isEqualTo(LocalDate.parse("2026-11-15"));
    assertThat(ChatWindows.day(late, ZoneOffset.UTC)).isEqualTo(DAY);
    assertThat(ChatWindows.windowId("19:abc@thread.v2", DAY)).isEqualTo("chat:19:abc@thread.v2:2026-11-14");
  }

  @Test
  void buildsTurnsOldestFirst() {
    List<JsonNode> messages = List.of(
      message("3", "Ada", T.plusSeconds(120), "<p>Third</p>"),
      message("2", "Bo", T.plusSeconds(60), "<p>Deploy <b>now</b></p>"),
      message("1", "Ada", T, "<p>First</p>"));

    HarvestRecord.ConversationThread thread = ChatWindows.build(chat("Release"), List.of("Ada", "Bo"), DAY, messages);

    assertThat(thread.id()).isEqualTo("chat:19:abc@thread.v2:2026-11-14");
    assertThat(thread.turns()).extracting(HarvestRecord.Turn::id).containsExactly("1", "2", "3");
    assertThat(thread.turns()).extracting(HarvestRecord.Turn::author).containsExactly("Ada", "Bo", "Ada");
    assertThat(thread.turns().get(1).text()).contains("Deploy").contains("now").doesNotContain("<b>");
    assertThat(thread.turns().getFirst().timestamp()).isEqualTo(T);
    assertThat(thread.metadata()).containsEntry("kind", "chat").containsEntry("chatType", "group").containsEntry("date", "2026-11-14");
  }

  @Test
  void skipsSystemDeletedAndTrivial() {
    ObjectNode system = message("1", "Ada", T, "<systemEventMessage/>").put("messageType", "systemEventMessage");
    ObjectNode deleted = message("2", "Ada", T.plusSeconds(1), "<p>gone</p>").put("deletedDateTime", T.plusSeconds(9).toString());
    List<JsonNode> messages = List.of(system, deleted, message("3", "Bo", T.plusSeconds(2), "👍"),
      message("4", "Bo", T.plusSeconds(3), "<p>Thanks!</p>"), message("5", "Bo", T.plusSeconds(4), "ok"),
      message("6", "Ada", T.plusSeconds(5), "<p>Let's move the release to Friday</p>"));

    HarvestRecord.ConversationThread thread = ChatWindows.build(chat("Release"), List.of(), DAY, messages);

    assertThat(thread.turns()).extracting(HarvestRecord.Turn::id).containsExactly("6");
  }

  @Test
  void nothingLeftIsNull() {
    assertThat(ChatWindows.build(chat(null), List.of("Ada"), DAY,
      List.of(message("1", "Ada", T, "ok"), message("2", "Bo", T.plusSeconds(1), "<p>thank you</p>")))).isNull();
  }

  @Test
  void titleFromTopicOrMembers() {
    List<JsonNode> one = List.of(message("1", "Ada", T, "<p>Hello there</p>"));

    assertThat(ChatWindows.build(chat("Release"), List.of("Ada"), DAY, one).title()).isEqualTo("Release · 2026-11-14");
    assertThat(ChatWindows.build(chat(null), List.of("Ada", "Bo"), DAY, one).title()).isEqualTo("Chat with Ada, Bo · 2026-11-14");
    assertThat(ChatWindows.build(chat(null), List.of("A", "B", "C", "D", "E", "F", "G"), DAY, one).title())
      .isEqualTo("Chat with A, B, C, D, E and 2 more · 2026-11-14");
    assertThat(ChatWindows.build(chat("  "), List.of(), DAY, one).title()).isEqualTo("Chat · 2026-11-14");
  }

  @Test
  void urlFallsBackToTheChatId() {
    List<JsonNode> one = List.of(message("1", "Ada", T, "<p>Hello there</p>"));

    assertThat(ChatWindows.build(chat("R"), List.of(), DAY, one).url())
      .isEqualTo("https://teams.microsoft.com/l/chat/19%3Aabc%40thread.v2/0");
    ObjectNode linked = chat("R").put("webUrl", "https://teams.microsoft.com/l/chat/x/0?tenantId=t");
    assertThat(ChatWindows.build(linked, List.of(), DAY, one).url()).isEqualTo("https://teams.microsoft.com/l/chat/x/0?tenantId=t");
  }

  @Test
  void hugeDayKeepsTheOldestTurns() {
    List<JsonNode> messages = new ArrayList<>();
    for (int i = 6_000; i >= 1; i--) {
      messages.add(message(String.valueOf(i), "Ada", T.plusMillis(i), "<p>message number " + i + "</p>"));
    }

    HarvestRecord.ConversationThread thread = ChatWindows.build(chat("R"), List.of(), DAY, messages);

    assertThat(thread.turns()).hasSize(ProtocolLimits.MAX_TURNS);
    assertThat(thread.turns().getFirst().id()).isEqualTo("1");
  }

  @Test
  void sameTimeOrdersByNumericId() {
    List<JsonNode> messages = List.of(message("10", "Ada", T, "<p>ten</p>"), message("9", "Ada", T, "<p>nine</p>"));

    assertThat(ChatWindows.build(chat("R"), List.of(), DAY, messages).turns()).extracting(HarvestRecord.Turn::id).containsExactly("9", "10");
  }
}
