package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A channel post and its replies as one thread. */
final class ChannelThreads {

  private static final int TITLE_LINE_CHARS = 80;

  private ChannelThreads() {
  }

  static String threadId(String teamId, String channelId, String postId) {
    return "channel:" + teamId + ":" + channelId + ":" + postId;
  }

  /** The thread, or null when the post itself is deleted, a system event or empty. */
  static HarvestRecord.ConversationThread build(String teamId, String channelId, String teamName, String channelName, JsonNode post,
                                                List<JsonNode> replies) {
    if (TeamsMessages.skipped(post)) {
      return null;
    }
    List<JsonNode> messages = new ArrayList<>();
    messages.add(post);
    messages.addAll(replies);
    List<HarvestRecord.Turn> turns = TeamsMessages.turns(messages, true);
    if (turns.isEmpty()) {
      return null;
    }
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("kind", "channel");
    metadata.put("team", cut(teamName, ProtocolLimits.MAX_METADATA_VALUE_CHARS));
    metadata.put("channel", cut(channelName, ProtocolLimits.MAX_METADATA_VALUE_CHARS));
    String webUrl = post.path("webUrl").asString("");
    String url = webUrl.isBlank() || webUrl.length() > ProtocolLimits.MAX_URL_CHARS ? "https://teams.microsoft.com" : webUrl;
    return new HarvestRecord.ConversationThread(threadId(teamId, channelId, post.path("id").asString()), title(teamName, channelName, post), url, turns, metadata);
  }

  private static String title(String teamName, String channelName, JsonNode post) {
    String subject = post.path("subject").asString("").strip();
    String about = subject.isEmpty() ? cut(TeamsMessages.text(post).lines().findFirst().orElse("").strip(), TITLE_LINE_CHARS) : subject;
    return cut(teamName + " / " + channelName + ": " + about, ProtocolLimits.MAX_TITLE_CHARS);
  }

  private static String cut(String value, int max) {
    return value.length() > max ? value.substring(0, max) : value;
  }
}
