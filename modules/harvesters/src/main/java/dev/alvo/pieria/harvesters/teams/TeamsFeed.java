package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.RecordSink;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvesters.http.JsonTransport;
import dev.alvo.pieria.harvesters.incremental.Change;
import dev.alvo.pieria.harvesters.incremental.ChangeFeed;
import dev.alvo.pieria.harvesters.incremental.Page;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The signed-in user's chats, as one item per chat per day, and the configured channels, as one item
 * per post. Graph has no delegated change feed for chats, so each chat is asked for messages modified
 * since the bound. Listing every live item would mean reading every message ever sent, so this feed
 * never sweeps; a day or post that loads as nothing is reported gone instead.
 */
public final class TeamsFeed implements ChangeFeed<JsonNode> {

  /** A chat whose last message is this much older than the bound is not asked for edits. */
  public static final Duration QUIET_CHAT = Duration.ofDays(7);
  public static final int PAGE = 50;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DateTimeFormatter FILTER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final Set<String> CHAT_TYPES = Set.of("oneOnOne", "group", "meeting");
  private static final String CHATS = "chats:";
  private static final String CHANNEL = "channel:";

  private record ResolvedChannel(String teamId, String teamName, String channelId, String channelName) {
  }

  private final TeamsConfig config;
  private final JsonTransport transport;
  private final RecordSink sink;
  private final Map<String, List<String>> members = new HashMap<>();
  private TeamsSession session;
  private List<ResolvedChannel> channels = List.of();
  private Instant horizon;

  public TeamsFeed(TeamsConfig config, JsonTransport transport, RecordSink sink) {
    this.config = config;
    this.transport = transport;
    this.sink = sink;
  }

  /** Signs in, saving a rotated refresh token first, then finds the configured channels. */
  @Override
  public Instant begin() throws HarvesterException {
    session = TeamsSession.open(config, transport, sink);
    channels = resolveChannels();
    Instant now = session.graph().serverTime();
    horizon = now.minus(Duration.ofDays(config.sinceDays()));
    return now;
  }

  private GraphClient graph() {
    return session.graph();
  }

  private List<ResolvedChannel> resolveChannels() throws HarvesterException {
    if (config.channels().isEmpty()) {
      return List.of();
    }
    List<JsonNode> teams = all("/me/joinedTeams?$select=id,displayName");
    List<ResolvedChannel> resolved = new ArrayList<>();
    for (ChannelRef ref : config.channels()) {
      JsonNode team = teams.stream().filter(candidate -> candidate.path("displayName").asString("").strip().equalsIgnoreCase(ref.team()))
        .findFirst().orElseThrow(() -> new HarvesterException(ErrorClass.CONFIG, "you are not a member of a team named " + ref.team()));
      String teamId = team.path("id").asString();
      String teamName = team.path("displayName").asString().strip();
      boolean found = false;
      for (JsonNode channel : all("/teams/" + segment(teamId) + "/channels?$select=id,displayName")) {
        String channelName = channel.path("displayName").asString("").strip();
        if (ref.matches(teamName, channelName)) {
          ResolvedChannel candidate = new ResolvedChannel(teamId, teamName, channel.path("id").asString(), channelName);
          if (!resolved.contains(candidate)) {
            resolved.add(candidate);
          }
          found = true;
        }
      }
      if (!found && ref.channel() != null) {
        throw new HarvesterException(ErrorClass.CONFIG, "team " + teamName + " has no channel named " + ref.channel());
      }
    }
    return resolved;
  }

  /** Chats first, then each channel in turn. */
  @Override
  public Page<Change<JsonNode>> changes(Instant from, String token) throws HarvesterException {
    Instant bound = from != null && from.isAfter(horizon) ? from : horizon;
    if (token == null) {
      return config.includeChats() ? chats(bound, null) : channel(bound, 0, null);
    }
    if (token.startsWith(CHATS)) {
      return chats(bound, token.substring(CHATS.length()));
    }
    if (token.startsWith(CHANNEL)) {
      String[] parts = token.substring(CHANNEL.length()).split(":", 2);
      int index;
      try {
        index = Integer.parseInt(parts[0]);
      } catch (NumberFormatException e) {
        throw new HarvesterException(ErrorClass.FATAL, "unknown teams page token");
      }
      if (index < 0 || index >= channels.size()) {
        throw new HarvesterException(ErrorClass.FATAL, "unknown teams page token");
      }
      return channel(bound, index, parts.length > 1 ? parts[1] : null);
    }
    throw new HarvesterException(ErrorClass.FATAL, "unknown teams page token");
  }

  private Page<Change<JsonNode>> chats(Instant bound, String link) throws HarvesterException {
    JsonNode page = link == null ? graph().get("/me/chats?$expand=lastMessagePreview&$top=" + PAGE) : graph().follow(link);
    List<Change<JsonNode>> changes = new ArrayList<>();
    Set<String> wanted = config.chatTypes().isEmpty() ? CHAT_TYPES : config.chatTypes();
    for (JsonNode chat : page.path("value")) {
      if (!wanted.contains(chat.path("chatType").asString("")) || quiet(chat, bound)) {
        continue;
      }
      changes.addAll(chatChanges(chat, bound));
    }
    String next = nextLink(page);
    if (next != null) {
      return new Page<>(changes, CHATS + next);
    }
    return new Page<>(changes, channels.isEmpty() ? null : CHANNEL + 0);
  }

  private static boolean quiet(JsonNode chat, Instant bound) {
    JsonNode last = chat.path("lastMessagePreview").path("createdDateTime");
    if (!last.isString()) {
      return true;
    }
    return Instant.parse(last.asString()).isBefore(bound.minus(QUIET_CHAT));
  }

  /** One change per day that has a message modified after the bound, at that day's latest modification. */
  private List<Change<JsonNode>> chatChanges(JsonNode chat, Instant bound) throws HarvesterException {
    String chatId = chat.path("id").asString();
    String path = "/chats/" + segment(chatId) + "/messages?$top=" + PAGE + "&$orderby=" + JsonTransport.encode("lastModifiedDateTime desc")
      + "&$filter=" + JsonTransport.encode("lastModifiedDateTime gt " + FILTER_TIME.format(bound));
    JsonNode page = graph().getOrNull(path);
    Map<String, Instant> windows = new LinkedHashMap<>();
    while (page != null) {
      for (JsonNode message : page.path("value")) {
        LocalDate day = ChatWindows.day(TeamsMessages.created(message), config.timeZone());
        if (day.atStartOfDay(config.timeZone()).toInstant().isBefore(horizon)) {
          continue;
        }
        Instant modified = TeamsMessages.modified(message);
        windows.merge(ChatWindows.windowId(chatId, day), modified, (a, b) -> a.isAfter(b) ? a : b);
      }
      String next = nextLink(page);
      page = next == null ? null : graph().follow(next);
    }
    List<Change<JsonNode>> changes = new ArrayList<>();
    windows.forEach((id, modified) -> changes.add(new Change<>(id, String.valueOf(modified.toEpochMilli()), modified, chat)));
    return changes;
  }

  private Page<Change<JsonNode>> channel(Instant bound, int index, String link) throws HarvesterException {
    if (channels.isEmpty()) {
      return new Page<>(List.of(), null);
    }
    ResolvedChannel channel = channels.get(index);
    JsonNode page = link == null
      ? graph().get(channelPath(channel) + "/messages/delta?$filter=" + JsonTransport.encode("lastModifiedDateTime gt " + FILTER_TIME.format(bound))
        + "&$expand=replies&$top=" + PAGE)
      : graph().follow(link);
    List<Change<JsonNode>> changes = new ArrayList<>();
    for (JsonNode post : page.path("value")) {
      Instant changed = TeamsMessages.modified(post);
      for (JsonNode reply : post.path("replies")) {
        Instant modified = TeamsMessages.modified(reply);
        changed = modified.isAfter(changed) ? modified : changed;
      }
      ObjectNode data = MAPPER.createObjectNode().put("teamId", channel.teamId()).put("teamName", channel.teamName())
        .put("channelId", channel.channelId()).put("channelName", channel.channelName());
      data.set("post", post);
      changes.add(new Change<>(ChannelThreads.threadId(channel.teamId(), channel.channelId(), post.path("id").asString()),
        String.valueOf(changed.toEpochMilli()), changed, data));
    }
    String next = nextLink(page);
    if (next != null) {
      return new Page<>(changes, CHANNEL + index + ":" + next);
    }
    return new Page<>(changes, index + 1 < channels.size() ? CHANNEL + (index + 1) : null);
  }

  /** Never any: Teams is not swept, so a run never deletes what it didn't hear was gone. */
  @Override
  public Page<String> liveIds(String token) {
    return new Page<>(List.of(), null);
  }

  @Override
  public boolean tombstonesGone() {
    return true;
  }

  @Override
  public List<HarvestRecord.Item> load(List<Change<JsonNode>> changes) throws HarvesterException {
    List<HarvestRecord.Item> items = new ArrayList<>();
    for (Change<JsonNode> change : changes) {
      HarvestRecord.Item item = change.id().startsWith("chat:") ? window(change) : thread(change);
      if (item != null) {
        items.add(item);
      }
    }
    return items;
  }

  /** The whole day, so an edit re-extracts only its own window. Null when the chat is gone. */
  private HarvestRecord.Item window(Change<JsonNode> change) throws HarvesterException {
    JsonNode chat = change.data();
    String chatId = chat.path("id").asString();
    LocalDate day = LocalDate.parse(change.id().substring(change.id().length() - "yyyy-MM-dd".length()));
    Instant start = day.atStartOfDay(config.timeZone()).toInstant();
    Instant end = day.plusDays(1).atStartOfDay(config.timeZone()).toInstant();
    String path = "/chats/" + segment(chatId) + "/messages?$top=" + PAGE + "&$orderby=" + JsonTransport.encode("createdDateTime desc")
      + "&$filter=" + JsonTransport.encode("createdDateTime lt " + FILTER_TIME.format(end));
    JsonNode page = graph().getOrNull(path);
    if (page == null) {
      return null;
    }
    List<JsonNode> messages = new ArrayList<>();
    boolean reachedStart = false;
    while (true) {
      for (JsonNode message : page.path("value")) {
        if (TeamsMessages.created(message).isBefore(start)) {
          reachedStart = true;
          break;
        }
        messages.add(message);
      }
      String next = nextLink(page);
      if (reachedStart || next == null) {
        break;
      }
      page = graph().follow(next);
    }
    String topic = chat.path("topic").asString("");
    List<String> others = topic.isBlank() ? otherMembers(chatId) : List.of();
    return ChatWindows.build(chat, others, day, messages);
  }

  private List<String> otherMembers(String chatId) throws HarvesterException {
    List<String> cached = members.get(chatId);
    if (cached != null) {
      return cached;
    }
    List<String> names = new ArrayList<>();
    JsonNode page = graph().getOrNull("/chats/" + segment(chatId) + "/members");
    while (page != null) {
      for (JsonNode member : page.path("value")) {
        String name = member.path("displayName").asString("").strip();
        if (!name.isEmpty() && !member.path("userId").asString("").equals(session.userId())) {
          names.add(name);
        }
      }
      String next = nextLink(page);
      page = next == null ? null : graph().follow(next);
    }
    members.put(chatId, List.copyOf(names));
    return names;
  }

  private HarvestRecord.Item thread(Change<JsonNode> change) throws HarvesterException {
    JsonNode data = change.data();
    JsonNode post = data.path("post");
    ResolvedChannel channel = new ResolvedChannel(data.path("teamId").asString(), data.path("teamName").asString(),
      data.path("channelId").asString(), data.path("channelName").asString());
    List<JsonNode> replies = new ArrayList<>();
    if (post.has("replies@odata.nextLink")) {
      replies.addAll(all(channelPath(channel) + "/messages/" + segment(post.path("id").asString()) + "/replies?$top=" + PAGE));
    } else {
      post.path("replies").forEach(replies::add);
    }
    return ChannelThreads.build(channel.teamId(), channel.channelId(), channel.teamName(), channel.channelName(), post, replies);
  }

  /** Every row of a listing, following its next links. */
  private List<JsonNode> all(String pathAndQuery) throws HarvesterException {
    List<JsonNode> rows = new ArrayList<>();
    JsonNode page = graph().get(pathAndQuery);
    while (page != null) {
      page.path("value").forEach(rows::add);
      String next = nextLink(page);
      page = next == null ? null : graph().follow(next);
    }
    return rows;
  }

  private static String channelPath(ResolvedChannel channel) {
    return "/teams/" + segment(channel.teamId()) + "/channels/" + segment(channel.channelId());
  }

  private static String nextLink(JsonNode page) {
    JsonNode next = page.get("@odata.nextLink");
    return next != null && next.isString() && !next.asString().isBlank() ? next.asString() : null;
  }

  /** A path segment. Teams ids hold {@code :} and {@code @}, which a path allows as they are. */
  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%3A", ":").replace("%40", "@");
  }
}
