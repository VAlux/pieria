package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvesters.ConfigValues;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A Teams source's settings, validated.
 *
 * @param chatTypes    Graph's spelling; empty for every type
 * @param refreshToken null until the source has signed in
 */
public record TeamsConfig(String tenant, String clientId, String loginUrl, String graphUrl, boolean includeChats,
                          Set<String> chatTypes, List<ChannelRef> channels, int sinceDays, ZoneId timeZone, String refreshToken) {

  public static final String REFRESH_TOKEN = "refreshToken";
  static final int MAX_SINCE_DAYS = 240;
  private static final Pattern TENANT = Pattern.compile("[A-Za-z0-9.-]+");
  private static final Pattern GUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final List<String> CHAT_TYPES = List.of("oneOnOne", "group", "meeting");
  private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");
  private static final List<String> CHAT_SCOPES = List.of("Chat.Read");
  private static final List<String> CHANNEL_SCOPES = List.of("Team.ReadBasic.All", "Channel.ReadBasic.All", "ChannelMessage.Read.All");

  public TeamsConfig {
    chatTypes = Set.copyOf(chatTypes);
    channels = List.copyOf(channels);
  }

  public static TeamsConfig parse(HarvestRequest request) throws HarvesterException {
    ObjectNode config = request.config();
    String tenant = ConfigValues.string(config, "tenant");
    tenant = tenant == null ? "organizations" : tenant;
    if (!TENANT.matcher(tenant).matches()) {
      throw ConfigValues.invalid("tenant must be a tenant id, a domain, or organizations");
    }
    if (tenant.equalsIgnoreCase("consumers")) {
      throw ConfigValues.invalid("personal Microsoft accounts can't read Teams through Graph; use your work tenant or organizations");
    }
    String clientId = ConfigValues.requiredString(config, "clientId");
    if (!GUID.matcher(clientId).matches()) {
      throw ConfigValues.invalid("clientId must be the application (client) ID, a GUID");
    }
    String loginUrl = address(config, "loginUrl", "https://login.microsoftonline.com");
    String graphUrl = address(config, "graphUrl", "https://graph.microsoft.com");
    JsonNode chatsFlag = config.get("includeChats");
    boolean includeChats = chatsFlag == null || chatsFlag.isNull() || ConfigValues.flag(config, "includeChats");
    Set<String> chatTypes = new LinkedHashSet<>();
    for (String value : ConfigValues.strings(config, "chatTypes")) {
      chatTypes.add(CHAT_TYPES.stream().filter(type -> type.equalsIgnoreCase(value.strip())).findFirst()
        .orElseThrow(() -> ConfigValues.invalid("chatTypes may hold only " + String.join(", ", CHAT_TYPES))));
    }
    List<ChannelRef> channels = new ArrayList<>();
    for (String value : ConfigValues.strings(config, "channels")) {
      channels.add(ChannelRef.parse(value));
    }
    if (!includeChats && channels.isEmpty()) {
      throw ConfigValues.invalid("nothing to harvest: turn on includeChats or name channels");
    }
    int sinceDays = ConfigValues.integer(config, "sinceDays", 90, 1, MAX_SINCE_DAYS);
    ZoneId timeZone = zone(ConfigValues.string(config, "timeZone"));
    String refreshToken = request.secrets().get(REFRESH_TOKEN);
    return new TeamsConfig(tenant, clientId, loginUrl, graphUrl, includeChats, chatTypes, channels, sinceDays, timeZone,
      refreshToken == null || refreshToken.isBlank() ? null : refreshToken);
  }

  /** What sign-in asks for, space-separated. */
  public String scopes() {
    return "offline_access " + String.join(" ", graphScopes());
  }

  /** The Graph permissions the source needs, in the order they are asked for. */
  public Set<String> graphScopes() {
    Set<String> scopes = new LinkedHashSet<>();
    scopes.add("User.Read");
    if (includeChats) {
      scopes.addAll(CHAT_SCOPES);
    }
    if (!channels.isEmpty()) {
      scopes.addAll(CHANNEL_SCOPES);
    }
    return scopes;
  }

  @Override
  public String toString() {
    return "TeamsConfig[tenant=" + tenant + ", clientId=" + clientId + ", loginUrl=" + loginUrl + ", graphUrl=" + graphUrl
      + ", includeChats=" + includeChats + ", chatTypes=" + chatTypes + ", channels=" + channels + ", sinceDays=" + sinceDays
      + ", timeZone=" + timeZone + ", signedIn=" + (refreshToken != null) + "]";
  }

  private static String address(ObjectNode config, String key, String fallback) throws HarvesterException {
    String value = ConfigValues.string(config, key);
    if (value == null) {
      return fallback;
    }
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
      String path = uri.getRawPath() == null ? "" : uri.getRawPath();
      boolean allowedScheme = scheme.equals("https") || (scheme.equals("http") && LOOPBACK.contains(host));
      if (!allowedScheme || host.isEmpty() || !(path.isEmpty() || path.equals("/")) || uri.getRawQuery() != null
        || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
        throw invalidAddress(key, fallback);
      }
      return scheme + "://" + uri.getRawAuthority();
    } catch (URISyntaxException e) {
      throw invalidAddress(key, fallback);
    }
  }

  private static HarvesterException invalidAddress(String key, String example) {
    return ConfigValues.invalid(key + " must be an https address with no path, such as " + example);
  }

  private static ZoneId zone(String value) throws HarvesterException {
    if (value == null) {
      return ZoneId.of("UTC").normalized();
    }
    try {
      return ZoneId.of(value);
    } catch (DateTimeException e) {
      throw ConfigValues.invalid("timeZone must be a zone such as Europe/Kyiv");
    }
  }
}
