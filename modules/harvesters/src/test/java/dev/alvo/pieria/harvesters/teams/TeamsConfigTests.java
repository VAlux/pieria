package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeamsConfigTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ObjectNode config() {
    return MAPPER.createObjectNode().put("clientId", StubMicrosoft.CLIENT_ID);
  }

  private static TeamsConfig parse(ObjectNode config) throws HarvesterException {
    return TeamsConfig.parse(new HarvestRequest(1, config, Map.of("refreshToken", "r0"), null, null));
  }

  @Test
  void defaults() throws HarvesterException {
    TeamsConfig parsed = parse(config());

    assertThat(parsed.tenant()).isEqualTo("organizations");
    assertThat(parsed.clientId()).isEqualTo(StubMicrosoft.CLIENT_ID);
    assertThat(parsed.loginUrl()).isEqualTo("https://login.microsoftonline.com");
    assertThat(parsed.graphUrl()).isEqualTo("https://graph.microsoft.com");
    assertThat(parsed.includeChats()).isTrue();
    assertThat(parsed.chatTypes()).isEmpty();
    assertThat(parsed.channels()).isEmpty();
    assertThat(parsed.sinceDays()).isEqualTo(90);
    assertThat(parsed.timeZone()).isEqualTo(ZoneOffset.UTC);
    assertThat(parsed.refreshToken()).isEqualTo("r0");
  }

  @Test
  void missingRefreshTokenIsNull() throws HarvesterException {
    assertThat(TeamsConfig.parse(new HarvestRequest(1, config(), Map.of(), null, null)).refreshToken()).isNull();
  }

  @Test
  void channelRefsSplitAtTheFirstSlash() throws HarvesterException {
    ObjectNode config = config();
    config.putArray("channels").add("Eng / Ops/Night").add("Sales");

    assertThat(parse(config).channels()).containsExactly(new ChannelRef("Eng", "Ops/Night"), new ChannelRef("Sales", null));
  }

  @Test
  void scopesGrowWithChannels() throws HarvesterException {
    assertThat(parse(config()).scopes()).isEqualTo("offline_access User.Read Chat.Read");
    ObjectNode withChannels = config();
    withChannels.putArray("channels").add("Eng");

    TeamsConfig parsed = parse(withChannels);

    assertThat(parsed.scopes())
      .isEqualTo("offline_access User.Read Chat.Read Team.ReadBasic.All Channel.ReadBasic.All ChannelMessage.Read.All");
    assertThat(parsed.graphScopes())
      .containsExactly("User.Read", "Chat.Read", "Team.ReadBasic.All", "Channel.ReadBasic.All", "ChannelMessage.Read.All");
  }

  @Test
  void channelsOnlyLeaveOutChatRead() throws HarvesterException {
    ObjectNode config = config().put("includeChats", false);
    config.putArray("channels").add("Eng");

    assertThat(parse(config).scopes()).doesNotContain("Chat.Read ");
    assertThat(parse(config).graphScopes()).doesNotContain("Chat.Read");
  }

  @Test
  void chatTypesKeepGraphSpelling() throws HarvesterException {
    ObjectNode config = config();
    config.putArray("chatTypes").add("oneonone").add("group");

    assertThat(parse(config).chatTypes()).isEqualTo(Set.of("oneOnOne", "group"));
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
    "clientId|\"abc\"|clientId must be the application (client) ID, a GUID",
    "tenant|\"consumers\"|personal Microsoft accounts can't read Teams through Graph",
    "tenant|\"a/b\"|tenant",
    "loginUrl|\"http://login.example.com\"|loginUrl must be an https address",
    "graphUrl|\"https://graph.microsoft.com/v1.0\"|graphUrl must be an https address",
    "graphUrl|\"https://graph.microsoft.com?x=1\"|graphUrl must be an https address",
    "chatTypes|[\"channel\"]|chatTypes",
    "sinceDays|241|sinceDays must be a whole number from 1 to 240",
    "timeZone|\"Mars/Base\"|timeZone must be a zone such as Europe/Kyiv",
    "channels|[\"/x\"]|channels",
    "channels|[\"Eng/ \"]|channels",
    "includeChats|false|nothing to harvest: turn on includeChats or name channels"
  })
  void rejects(String key, String value, String fragment) {
    ObjectNode config = config();
    config.set(key, MAPPER.readTree(value));

    assertThatThrownBy(() -> parse(config)).isInstanceOfSatisfying(HarvesterException.class, e -> {
      assertThat(e.errorClass()).isEqualTo(ErrorClass.CONFIG);
      assertThat(e.getMessage()).contains(fragment);
    });
  }

  @Test
  void loopbackMayUseHttp() throws HarvesterException {
    TeamsConfig parsed = parse(config().put("loginUrl", "http://127.0.0.1:1234/").put("graphUrl", "http://localhost:99"));

    assertThat(parsed.loginUrl()).isEqualTo("http://127.0.0.1:1234");
    assertThat(parsed.graphUrl()).isEqualTo("http://localhost:99");
  }
}
