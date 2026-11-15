package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One day of a chat, in the source's time zone, as one thread. */
final class ChatWindows {

  private static final int NAMED_MEMBERS = 5;

  private ChatWindows() {
  }

  static String windowId(String chatId, LocalDate day) {
    return "chat:" + chatId + ":" + day;
  }

  static LocalDate day(Instant created, ZoneId zone) {
    return created.atZone(zone).toLocalDate();
  }

  /** The day's thread, or null when no message in it is worth a turn. */
  static HarvestRecord.ConversationThread build(JsonNode chat, List<String> otherMembers, LocalDate day, List<JsonNode> messages) {
    List<HarvestRecord.Turn> turns = TeamsMessages.turns(messages, false);
    if (turns.isEmpty()) {
      return null;
    }
    String chatId = chat.path("id").asString();
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("kind", "chat");
    String chatType = chat.path("chatType").asString("");
    if (!chatType.isBlank()) {
      metadata.put("chatType", chatType);
    }
    metadata.put("date", day.toString());
    return new HarvestRecord.ConversationThread(windowId(chatId, day), title(chat, otherMembers, day), url(chat), turns, metadata);
  }

  private static String title(JsonNode chat, List<String> otherMembers, LocalDate day) {
    String topic = chat.path("topic").asString("").strip();
    String name;
    if (!topic.isEmpty()) {
      name = topic;
    } else if (!otherMembers.isEmpty()) {
      name = "Chat with " + String.join(", ", otherMembers.subList(0, Math.min(NAMED_MEMBERS, otherMembers.size())));
      if (otherMembers.size() > NAMED_MEMBERS) {
        name += " and " + (otherMembers.size() - NAMED_MEMBERS) + " more";
      }
    } else {
      name = "Chat";
    }
    String title = name + " · " + day;
    return title.length() > ProtocolLimits.MAX_TITLE_CHARS ? title.substring(0, ProtocolLimits.MAX_TITLE_CHARS) : title;
  }

  private static String url(JsonNode chat) {
    String webUrl = chat.path("webUrl").asString("");
    if (!webUrl.isBlank() && webUrl.length() <= ProtocolLimits.MAX_URL_CHARS) {
      return webUrl;
    }
    return "https://teams.microsoft.com/l/chat/" + URLEncoder.encode(chat.path("id").asString(), StandardCharsets.UTF_8) + "/0";
  }
}
