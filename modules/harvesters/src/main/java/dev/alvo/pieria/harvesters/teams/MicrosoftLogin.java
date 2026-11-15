package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvesters.http.JsonTransport;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Microsoft's device-code sign-in and token refresh for a public client, against {@code loginUrl}. */
public final class MicrosoftLogin {

  static final String SIGN_IN_AGAIN = "sign in again with 'pieria source authorize'";
  private static final String LABEL = "Microsoft sign-in";
  private static final String GRAPH_PREFIX = "https://graph.microsoft.com/";
  private static final int DEFAULT_INTERVAL = 5;
  private static final int SLOW_DOWN_SECONDS = 5;
  private static final String ADMIN_SCOPE = "ChannelMessage.Read.All";

  private final TeamsConfig config;
  private final JsonTransport transport;

  public MicrosoftLogin(TeamsConfig config, JsonTransport transport) {
    this.config = config;
    this.transport = transport;
  }

  /** Asks for a code the user enters at Microsoft's page. A client Microsoft refuses is a config error. */
  public DeviceCodeGrant start() throws HarvesterException {
    JsonTransport.Response response = post("devicecode", form("client_id", config.clientId(), "scope", config.scopes()));
    JsonNode body = body(response);
    if (response.status() != 200) {
      throw new HarvesterException(ErrorClass.CONFIG, refusal(body, response.status()));
    }
    String deviceCode = text(body, "device_code");
    String userCode = text(body, "user_code");
    String verificationUri = text(body, "verification_uri");
    int expiresIn = body.path("expires_in").asInt(0);
    if (deviceCode == null || userCode == null || verificationUri == null || expiresIn <= 0) {
      throw new HarvesterException(ErrorClass.TRANSIENT, LABEL + " answered without a usable code");
    }
    JsonNode interval = body.get("interval");
    return new DeviceCodeGrant(deviceCode, userCode, verificationUri, text(body, "message"), expiresIn,
      interval == null || !interval.canConvertToInt() ? DEFAULT_INTERVAL : Math.max(0, interval.asInt()));
  }

  /** Polls until the user signs in, declines, or the code runs out. */
  public Tokens await(DeviceCodeGrant grant) throws HarvesterException {
    Instant giveUp = Instant.now().plusSeconds(grant.expiresIn());
    int interval = grant.interval();
    String form = form("grant_type", "urn:ietf:params:oauth:grant-type:device_code", "client_id", config.clientId(),
      "device_code", grant.deviceCode());
    while (true) {
      pause(interval);
      if (Instant.now().isAfter(giveUp)) {
        throw expired();
      }
      JsonTransport.Response response = post("token", form);
      JsonNode body = body(response);
      if (response.status() == 200) {
        return tokens(body);
      }
      switch (body.path("error").asString("")) {
        case "authorization_pending" -> {
        }
        case "slow_down" -> interval += SLOW_DOWN_SECONDS;
        case "authorization_declined" -> throw new HarvesterException(ErrorClass.AUTH, "the sign-in was declined");
        case "expired_token" -> throw expired();
        default -> throw new HarvesterException(ErrorClass.AUTH, refusal(body, response.status()));
      }
    }
  }

  /** Trades the stored refresh token for new tokens; Microsoft usually rotates the refresh token too. */
  public Tokens refresh(String refreshToken) throws HarvesterException {
    JsonTransport.Response response = post("token", form("grant_type", "refresh_token", "client_id", config.clientId(),
      "refresh_token", refreshToken, "scope", config.scopes()));
    JsonNode body = body(response);
    if (response.status() == 200) {
      return tokens(body);
    }
    String error = body.path("error").asString("");
    if (consentRequired(body)) {
      // Entra refuses the refresh outright rather than granting less, so this is where a missing
      // channel permission shows up after channels are added to a chats-only sign-in.
      throw new HarvesterException(ErrorClass.AUTH, config.channels().isEmpty()
        ? "Microsoft has no consent for the permissions this source needs; " + SIGN_IN_AGAIN
        : missingScope(ADMIN_SCOPE));
    }
    if (error.equals("invalid_grant") || error.equals("interaction_required")) {
      throw new HarvesterException(ErrorClass.AUTH, "the sign-in has expired or was revoked; " + SIGN_IN_AGAIN);
    }
    throw new HarvesterException(ErrorClass.AUTH, refusal(body, response.status()));
  }

  /**
   * Fails for a permission the source needs that the sign-in didn't grant. The one that needs an
   * admin is named first, as it is the one a user can't grant by signing in again.
   */
  public static void requireScopes(Tokens tokens, TeamsConfig config) throws HarvesterException {
    List<String> missing = config.graphScopes().stream().filter(scope -> !tokens.scopes().contains(scope.toLowerCase(Locale.ROOT))).toList();
    if (missing.isEmpty()) {
      return;
    }
    throw new HarvesterException(ErrorClass.AUTH, missingScope(missing.contains(ADMIN_SCOPE) ? ADMIN_SCOPE : missing.getFirst()));
  }

  private static String missingScope(String scope) {
    String message = "Microsoft did not grant " + scope + "; " + SIGN_IN_AGAIN;
    if (scope.equals(ADMIN_SCOPE)) {
      message += " (reading channels needs an admin to consent to " + ADMIN_SCOPE + ")";
    }
    return message;
  }

  /** AADSTS65001: nobody consented to a permission the request asks for. */
  private static boolean consentRequired(JsonNode body) {
    if (body.path("suberror").asString("").equals("consent_required")) {
      return true;
    }
    for (JsonNode code : body.path("error_codes")) {
      if (code.asLong(0) == 65001) {
        return true;
      }
    }
    return false;
  }

  private JsonTransport.Response post(String endpoint, String form) throws HarvesterException {
    HttpRequest request = transport.request(URI.create(config.loginUrl() + "/" + config.tenant() + "/oauth2/v2.0/" + endpoint))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(form))
      .build();
    return transport.send(request);
  }

  /** The JSON body of a 200 or of an OAuth error; anything else is Microsoft having trouble. */
  private static JsonNode body(JsonTransport.Response response) throws HarvesterException {
    int status = response.status();
    if (status != 200 && (status < 400 || status >= 500)) {
      throw new HarvesterException(ErrorClass.TRANSIENT, LABEL + " failed (HTTP " + status + ")");
    }
    return JsonTransport.json(response.body(), LABEL);
  }

  private static Tokens tokens(JsonNode body) throws HarvesterException {
    String accessToken = text(body, "access_token");
    if (accessToken == null) {
      throw new HarvesterException(ErrorClass.TRANSIENT, LABEL + " answered without an access token");
    }
    String refreshToken = text(body, "refresh_token");
    if (refreshToken == null) {
      throw new HarvesterException(ErrorClass.AUTH, "Microsoft returned no refresh token; the app needs the offline_access permission");
    }
    Set<String> scopes = new HashSet<>();
    for (String scope : body.path("scope").asString("").split("\\s+")) {
      String lowered = scope.toLowerCase(Locale.ROOT);
      if (lowered.startsWith(GRAPH_PREFIX)) {
        lowered = lowered.substring(GRAPH_PREFIX.length());
      }
      if (!lowered.isEmpty()) {
        scopes.add(lowered);
      }
    }
    return new Tokens(accessToken, refreshToken, scopes);
  }

  /** Microsoft's description puts the useful sentence first and trace ids after it. */
  private static String refusal(JsonNode body, int status) {
    String description = text(body, "error_description");
    String detail = description == null ? text(body, "error") : description.lines().findFirst().orElse("").strip();
    return "Microsoft refused the sign-in: " + (detail == null || detail.isEmpty() ? "HTTP " + status : detail);
  }

  private static HarvesterException expired() {
    return new HarvesterException(ErrorClass.AUTH, "the code expired before anyone signed in");
  }

  private static String text(JsonNode body, String field) {
    JsonNode node = body.get(field);
    return node != null && node.isString() && !node.asString().isBlank() ? node.asString() : null;
  }

  private static String form(String... pairs) {
    StringBuilder form = new StringBuilder();
    for (int i = 0; i < pairs.length; i += 2) {
      if (!form.isEmpty()) {
        form.append('&');
      }
      form.append(pairs[i]).append('=').append(JsonTransport.encode(pairs[i + 1]));
    }
    return form.toString();
  }

  private static void pause(int seconds) throws HarvesterException {
    if (seconds <= 0) {
      return;
    }
    try {
      Thread.sleep(Duration.ofSeconds(seconds));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HarvesterException(ErrorClass.TRANSIENT, "interrupted while waiting for the sign-in");
    }
  }
}
