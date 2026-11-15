package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvesters.JsonStub;
import dev.alvo.pieria.harvesters.http.JsonTransport;
import dev.alvo.pieria.harvesters.incremental.Change;
import dev.alvo.pieria.harvesters.incremental.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The stub's clock is the real one, because the JDK server writes its own {@code Date} header, so
 * days are counted back from today.
 */
class TeamsFeedTests {

  private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

  private final StubMicrosoft microsoft = new StubMicrosoft();
  private final List<HarvestRecord> emitted = new ArrayList<>();

  @AfterEach
  void stop() {
    microsoft.close();
  }

  private static LocalDate daysAgo(int days) {
    return TODAY.minusDays(days);
  }

  private static Instant at(LocalDate day, int hour, int minute) {
    return day.atTime(LocalTime.of(hour, minute)).toInstant(ZoneOffset.UTC);
  }

  private TeamsFeed feed(ObjectNode config) throws HarvesterException {
    TeamsConfig parsed = TeamsConfig.parse(new HarvestRequest(1, config, Map.of("refreshToken", "r1"), null, null));
    TeamsFeed feed = new TeamsFeed(parsed, new JsonTransport("Microsoft", Duration.ofSeconds(10)), emitted::add);
    feed.begin();
    return feed;
  }

  private static List<Change<JsonNode>> all(TeamsFeed feed, Instant from) throws HarvesterException {
    List<Change<JsonNode>> changes = new ArrayList<>();
    String token = null;
    do {
      Page<Change<JsonNode>> page = feed.changes(from, token);
      changes.addAll(page.entries());
      token = page.next();
    } while (token != null);
    return changes;
  }

  private ObjectNode withChannels(String... refs) {
    ObjectNode config = microsoft.config();
    for (String ref : refs) {
      config.withArray("channels").add(ref);
    }
    return config;
  }

  @Test
  void firstRunReachesBackSinceDays() throws HarvesterException {
    StubMicrosoft.Chat chat = microsoft.chat("c1", "group", "Release");
    chat.message("1", "Ada", at(daysAgo(4), 9, 0), "<p>old plan</p>").put("lastModifiedDateTime", at(daysAgo(1), 9, 0).toString());

    List<Change<JsonNode>> changes = all(feed(microsoft.config().put("sinceDays", 3)), null);

    String filter = microsoft.requests("/v1.0/chats/c1/messages").getFirst().query().get("$filter");
    assertThat(filter).matches("lastModifiedDateTime gt \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");
    assertThat(Instant.parse(filter.substring("lastModifiedDateTime gt ".length())))
      .isCloseTo(Instant.now().minus(Duration.ofDays(3)), within(Duration.ofSeconds(30)));
    assertThat(changes).isEmpty();
  }

  @Test
  void oneWindowPerChatPerDay() throws HarvesterException {
    StubMicrosoft.Chat chat = microsoft.chat("c1", "group", "Release");
    chat.message("1", "Ada", at(daysAgo(2), 9, 0), "<p>one</p>");
    chat.message("2", "Bo", at(daysAgo(2), 10, 0), "<p>two</p>").put("lastModifiedDateTime", at(daysAgo(2), 11, 0).toString());
    chat.message("3", "Ada", at(daysAgo(1), 9, 0), "<p>three</p>");

    List<Change<JsonNode>> changes = all(feed(microsoft.config()), null);

    assertThat(changes).extracting(Change::id).containsExactlyInAnyOrder("chat:c1:" + daysAgo(2), "chat:c1:" + daysAgo(1));
    Change<JsonNode> older = changes.stream().filter(c -> c.id().endsWith(daysAgo(2).toString())).findFirst().orElseThrow();
    assertThat(older.version()).isEqualTo(String.valueOf(at(daysAgo(2), 11, 0).toEpochMilli()));
    assertThat(older.changedAt()).isEqualTo(at(daysAgo(2), 11, 0));
    assertThat(microsoft.requests("/v1.0/chats/c1/messages").getFirst().query())
      .containsEntry("$orderby", "lastModifiedDateTime desc").containsEntry("$top", "50");
    assertThat(microsoft.requests("/v1.0/me/chats").getFirst().query()).containsEntry("$expand", "lastMessagePreview");
  }

  @Test
  void quietChatIsNotQueried() throws HarvesterException {
    microsoft.chat("c1", "group", "Old").message("1", "Ada", at(daysAgo(3 + 8), 9, 0), "<p>long ago</p>");

    all(feed(microsoft.config().put("sinceDays", 3)), null);

    assertThat(microsoft.requests("/v1.0/chats/c1/messages")).isEmpty();
  }

  @Test
  void chatTypesFilter() throws HarvesterException {
    microsoft.chat("c1", "oneOnOne", null).message("1", "Ada", at(daysAgo(1), 9, 0), "<p>direct</p>");
    microsoft.chat("c2", "group", "G").message("2", "Ada", at(daysAgo(1), 9, 0), "<p>group</p>");
    ObjectNode config = microsoft.config();
    config.putArray("chatTypes").add("group");

    List<Change<JsonNode>> changes = all(feed(config), null);

    assertThat(changes).extracting(Change::id).containsExactly("chat:c2:" + daysAgo(1));
    assertThat(microsoft.requests("/v1.0/chats/c1/messages")).isEmpty();
  }

  @Test
  void loadReadsTheWholeDay() throws HarvesterException {
    StubMicrosoft.Chat chat = microsoft.chat("c1", "group", "Release");
    chat.message("1", "Ada", at(daysAgo(3), 23, 0), "<p>before</p>");
    chat.message("2", "Ada", at(daysAgo(2), 0, 0), "<p>first of the day</p>");
    chat.message("3", "Bo", at(daysAgo(2), 23, 59), "<p>last of the day</p>");
    chat.message("4", "Ada", at(daysAgo(1), 0, 0), "<p>after</p>");
    TeamsFeed feed = feed(microsoft.config());
    Change<JsonNode> window = all(feed, null).stream().filter(c -> c.id().equals("chat:c1:" + daysAgo(2))).findFirst().orElseThrow();

    HarvestRecord.ConversationThread thread = (HarvestRecord.ConversationThread) feed.load(List.of(window)).getFirst();

    assertThat(thread.turns()).extracting(HarvestRecord.Turn::id).containsExactly("2", "3");
    JsonStub.Request load = microsoft.requests("/v1.0/chats/c1/messages").getLast();
    assertThat(load.query()).containsEntry("$orderby", "createdDateTime desc")
      .containsEntry("$filter", "createdDateTime lt " + daysAgo(1) + "T00:00:00.000Z");
  }

  @Test
  void loadPagesUntilTheDayStarts() throws HarvesterException {
    StubMicrosoft.Chat chat = microsoft.chat("c1", "group", "Busy");
    for (int i = 0; i < 120; i++) {
      chat.message(String.valueOf(1_000 + i), "Ada", at(daysAgo(2), 8, 0).plusSeconds(i * 60L), "<p>message " + i + "</p>");
    }
    TeamsFeed feed = feed(microsoft.config());
    Change<JsonNode> window = all(feed, null).getFirst();

    HarvestRecord.ConversationThread thread = (HarvestRecord.ConversationThread) feed.load(List.of(window)).getFirst();

    assertThat(thread.turns()).hasSize(120);
  }

  @Test
  void localZoneShiftsTheDay() throws HarvesterException {
    microsoft.chat("c1", "group", "Late").message("1", "Ada", at(daysAgo(2), 22, 30), "<p>late night</p>");

    List<Change<JsonNode>> changes = all(feed(microsoft.config().put("timeZone", "Europe/Kyiv")), null);

    assertThat(changes).extracting(Change::id).containsExactly("chat:c1:" + daysAgo(1));
  }

  @Test
  void untitledChatIsNamedForTheOthers() throws HarvesterException {
    StubMicrosoft.Chat chat = microsoft.chat("c1", "oneOnOne", null).member("u1", "Me Myself").member("u2", "Ada");
    chat.message("1", "Ada", at(daysAgo(2), 9, 0), "<p>hello there</p>");
    TeamsFeed feed = feed(microsoft.config());

    HarvestRecord.Item item = feed.load(all(feed, null)).getFirst();

    assertThat(((HarvestRecord.ConversationThread) item).title()).isEqualTo("Chat with Ada · " + daysAgo(2));
  }

  @Test
  void goneChatLeavesTheWindowOut() throws HarvesterException {
    microsoft.chat("c1", "group", "Gone").message("1", "Ada", at(daysAgo(2), 9, 0), "<p>hello there</p>");
    TeamsFeed feed = feed(microsoft.config());
    List<Change<JsonNode>> changes = all(feed, null);
    microsoft.graph("/v1.0/chats/c1/messages", request -> JsonStub.Reply.status(404));

    assertThat(feed.load(changes)).isEmpty();
    assertThat(feed.tombstonesGone()).isTrue();
    assertThat(feed.liveIds(null).entries()).isEmpty();
  }

  @Test
  void channelsAreResolvedByName() throws HarvesterException {
    StubMicrosoft.Team eng = microsoft.team("t1", "Eng");
    eng.channel("c1", "Ops").post("100", "Ada", at(daysAgo(1), 9, 0), "<p>outage</p>");
    eng.channel("c2", "General").post("200", "Ada", at(daysAgo(1), 9, 0), "<p>hello</p>");

    List<Change<JsonNode>> changes = all(feed(withChannels("eng/ops").put("includeChats", false)), null);

    assertThat(changes).extracting(Change::id).containsExactly("channel:t1:c1:100");
    assertThat(microsoft.requests("/v1.0/teams/t1/channels/c2/messages/delta")).isEmpty();
    assertThat(microsoft.requests("/v1.0/me/chats")).isEmpty();
    assertThat(microsoft.requests("/v1.0/teams/t1/channels/c1/messages/delta").getFirst().query())
      .containsEntry("$expand", "replies").containsKey("$filter");
  }

  @Test
  void teamOnlyRefTakesEveryChannel() throws HarvesterException {
    StubMicrosoft.Team eng = microsoft.team("t1", "Eng");
    eng.channel("c1", "Ops").post("100", "Ada", at(daysAgo(1), 9, 0), "<p>outage</p>");
    eng.channel("c2", "General").post("200", "Ada", at(daysAgo(1), 9, 0), "<p>hello</p>");

    List<Change<JsonNode>> changes = all(feed(withChannels("Eng")), null);

    assertThat(changes).extracting(Change::id).containsExactlyInAnyOrder("channel:t1:c1:100", "channel:t1:c2:200");
  }

  @Test
  void unknownTeamIsConfig() {
    microsoft.team("t1", "Eng").channel("c1", "Ops");

    assertThatThrownBy(() -> feed(withChannels("Sales"))).isInstanceOfSatisfying(HarvesterException.class, e -> {
      assertThat(e.errorClass()).isEqualTo(ErrorClass.CONFIG);
      assertThat(e.getMessage()).isEqualTo("you are not a member of a team named Sales");
    });
  }

  @Test
  void unknownChannelIsConfig() {
    microsoft.team("t1", "Eng").channel("c1", "Ops");

    assertThatThrownBy(() -> feed(withChannels("Eng/Night"))).isInstanceOfSatisfying(HarvesterException.class, e -> {
      assertThat(e.errorClass()).isEqualTo(ErrorClass.CONFIG);
      assertThat(e.getMessage()).isEqualTo("team Eng has no channel named Night");
    });
  }

  @Test
  void newReplyChangesTheThreadVersion() throws HarvesterException {
    StubMicrosoft.Channel ops = microsoft.team("t1", "Eng").channel("c1", "Ops");
    ops.post("100", "Ada", at(daysAgo(1), 9, 0), "<p>outage</p>");
    TeamsFeed feed = feed(withChannels("Eng/Ops"));
    Change<JsonNode> before = all(feed, null).getFirst();

    ops.reply("100", "101", "Bo", at(daysAgo(1), 10, 0), "<p>looking into it</p>");
    Change<JsonNode> after = all(feed, null).getFirst();

    assertThat(Long.parseLong(after.version())).isGreaterThan(Long.parseLong(before.version()));
    assertThat(after.changedAt()).isEqualTo(at(daysAgo(1), 10, 0));
  }

  @Test
  void moreThanTheExpandedRepliesAreFetched() throws HarvesterException {
    StubMicrosoft.Channel ops = microsoft.team("t1", "Eng").channel("c1", "Ops");
    ops.post("100", "Ada", at(daysAgo(1), 9, 0), "<p>outage</p>");
    for (int i = 1; i <= 3; i++) {
      ops.reply("100", "10" + i, "Bo", at(daysAgo(1), 10, i), "<p>update " + i + "</p>");
    }
    microsoft.expandedReplies = 2;
    TeamsFeed feed = feed(withChannels("Eng/Ops"));

    HarvestRecord.ConversationThread thread = (HarvestRecord.ConversationThread) feed.load(all(feed, null)).getFirst();

    assertThat(thread.turns()).extracting(HarvestRecord.Turn::id).containsExactly("100", "101", "102", "103");
    assertThat(thread.title()).isEqualTo("Eng / Ops: outage");
    assertThat(microsoft.requests("/v1.0/teams/t1/channels/c1/messages/100/replies")).isNotEmpty();
  }

  @Test
  void foreignNextLinkIsTransient() throws HarvesterException {
    microsoft.chat("c1", "group", "G");
    microsoft.chatsNextLink = "https://evil.example.com/v1.0/me/chats?$skiptoken=x";
    TeamsFeed feed = feed(microsoft.config());

    assertThatThrownBy(() -> all(feed, null)).isInstanceOfSatisfying(HarvesterException.class, e -> {
      assertThat(e.errorClass()).isEqualTo(ErrorClass.TRANSIENT);
      assertThat(e.getMessage()).isEqualTo("graph returned a next link outside graphUrl");
    });
  }

  @Test
  void laterBoundReplacesTheHorizon() throws HarvesterException {
    microsoft.chat("c1", "group", "G").message("1", "Ada", at(daysAgo(1), 9, 0), "<p>x y z</p>");
    Instant from = at(daysAgo(1), 8, 0);

    all(feed(microsoft.config()), from);

    assertThat(microsoft.requests("/v1.0/chats/c1/messages").getFirst().query().get("$filter"))
      .isEqualTo("lastModifiedDateTime gt " + daysAgo(1) + "T08:00:00.000Z");
  }
}
