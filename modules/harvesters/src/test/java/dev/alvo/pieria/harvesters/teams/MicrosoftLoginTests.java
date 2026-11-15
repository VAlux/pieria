package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvesters.JsonStub;
import dev.alvo.pieria.harvesters.http.JsonTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicrosoftLoginTests {

  private final StubMicrosoft microsoft = new StubMicrosoft();

  @AfterEach
  void stop() {
    microsoft.close();
  }

  private TeamsConfig config(ObjectNode config) throws HarvesterException {
    return TeamsConfig.parse(new HarvestRequest(1, config, Map.of(), null, null));
  }

  private MicrosoftLogin login() throws HarvesterException {
    return login(microsoft.config());
  }

  private MicrosoftLogin login(ObjectNode config) throws HarvesterException {
    return new MicrosoftLogin(config(config), new JsonTransport("Microsoft", Duration.ofSeconds(10)));
  }

  private static void assertAuth(ThrowingCall call, String fragment) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(HarvesterException.class, e -> {
      assertThat(e.errorClass()).isEqualTo(ErrorClass.AUTH);
      assertThat(e.getMessage()).contains(fragment);
    });
  }

  @FunctionalInterface
  private interface ThrowingCall {
    void run() throws Exception;
  }

  @Test
  void deviceCodeIsRequestedWithClientAndScopes() throws HarvesterException {
    DeviceCodeGrant grant = login().start();

    JsonStub.Request request = microsoft.requests(StubMicrosoft.loginPath("devicecode")).getFirst();
    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.header("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
    assertThat(StubMicrosoft.form(request)).containsEntry("client_id", StubMicrosoft.CLIENT_ID)
      .containsEntry("scope", "offline_access User.Read Chat.Read");
    assertThat(grant.deviceCode()).isEqualTo(StubMicrosoft.DEVICE_CODE);
    assertThat(grant.userCode()).isEqualTo("ABCD-EFGH");
    assertThat(grant.verificationUri()).isEqualTo("https://microsoft.com/devicelogin");
    assertThat(grant.expiresIn()).isEqualTo(900);
    assertThat(grant.interval()).isZero();
    assertThat(grant.message()).contains("ABCD-EFGH");
  }

  @Test
  void intervalDefaultsToFive() throws HarvesterException {
    microsoft.deviceCode(StubMicrosoft.deviceCodeReply(900, null));

    assertThat(login().start().interval()).isEqualTo(5);
  }

  @Test
  void refusedClientAtTheDeviceCodeIsConfig() {
    microsoft.deviceCode(StubMicrosoft.oauthError("unauthorized_client",
      "AADSTS7000218: The request body must contain client_assertion.\r\nTrace ID: 1"));

    assertThatThrownBy(() -> login().start()).isInstanceOfSatisfying(HarvesterException.class, e -> {
      assertThat(e.errorClass()).isEqualTo(ErrorClass.CONFIG);
      assertThat(e.getMessage()).isEqualTo("Microsoft refused the sign-in: AADSTS7000218: The request body must contain client_assertion.");
    });
  }

  @Test
  void awaitPollsUntilTokens() throws HarvesterException {
    microsoft.deviceTokens(StubMicrosoft.oauthError("authorization_pending", "pending"),
      StubMicrosoft.oauthError("authorization_pending", "pending"),
      StubMicrosoft.tokens("at1", "r1", "https://graph.microsoft.com/User.Read https://graph.microsoft.com/Chat.Read openid"));
    MicrosoftLogin login = login();

    Tokens tokens = login.await(login.start());

    assertThat(tokens.accessToken()).isEqualTo("at1");
    assertThat(tokens.refreshToken()).isEqualTo("r1");
    assertThat(tokens.scopes()).contains("user.read", "chat.read");
    assertThat(microsoft.tokenForms()).hasSize(3).allSatisfy(form -> assertThat(form)
      .containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
      .containsEntry("device_code", StubMicrosoft.DEVICE_CODE)
      .containsEntry("client_id", StubMicrosoft.CLIENT_ID));
  }

  @Test
  void slowDownWidensTheInterval() throws HarvesterException {
    microsoft.deviceTokens(StubMicrosoft.oauthError("slow_down", "slow"), StubMicrosoft.tokens("at1", "r1", "User.Read Chat.Read"));
    MicrosoftLogin login = login();
    DeviceCodeGrant grant = login.start();
    Instant started = Instant.now();

    login.await(grant);

    assertThat(Duration.between(started, Instant.now())).isGreaterThanOrEqualTo(Duration.ofSeconds(5));
    assertThat(microsoft.tokenForms()).hasSize(2);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
    "authorization_declined|the sign-in was declined",
    "expired_token|the code expired before anyone signed in",
    "bad_verification_code|Microsoft refused the sign-in: AADSTS1: no"
  })
  void declinedAndExpiredAreAuth(String error, String message) throws HarvesterException {
    microsoft.deviceTokens(StubMicrosoft.oauthError(error, "AADSTS1: no\r\nTrace ID: 2"));
    MicrosoftLogin login = login();
    DeviceCodeGrant grant = login.start();

    assertAuth(() -> login.await(grant), message);
  }

  @Test
  void codeThatRunsOutLocallyIsExpired() throws HarvesterException {
    microsoft.deviceCode(StubMicrosoft.deviceCodeReply(2, 1));
    MicrosoftLogin login = login();
    DeviceCodeGrant grant = login.start();

    assertAuth(() -> login.await(grant), "the code expired before anyone signed in");
  }

  @Test
  void refreshSendsTheTokenAndScopes() throws HarvesterException {
    Tokens tokens = login().refresh("r0");

    Map<String, String> form = microsoft.tokenForms().getFirst();
    assertThat(form).containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", "r0")
      .containsEntry("client_id", StubMicrosoft.CLIENT_ID).containsEntry("scope", "offline_access User.Read Chat.Read");
    assertThat(tokens.refreshToken()).isEqualTo("r1");
  }

  @ParameterizedTest
  @CsvSource({"invalid_grant", "interaction_required"})
  void refreshRevokedIsAuthNamingAuthorize(String error) throws HarvesterException {
    microsoft.refresh(StubMicrosoft.oauthError(error, "AADSTS50173: The provided grant has expired"));
    MicrosoftLogin login = login();

    assertAuth(() -> login.refresh("r0"), "the sign-in has expired or was revoked; sign in again with 'pieria source authorize'");
  }

  @Test
  void serverErrorIsTransient() throws HarvesterException {
    microsoft.refresh(JsonStub.Reply.status(500));
    MicrosoftLogin login = login();

    assertThatThrownBy(() -> login.refresh("r0")).isInstanceOfSatisfying(HarvesterException.class,
      e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.TRANSIENT));
  }

  @Test
  void notJsonIsTransient() throws HarvesterException {
    microsoft.refresh(new JsonStub.Reply(200, Map.of(), "<html>"));
    MicrosoftLogin login = login();

    assertThatThrownBy(() -> login.refresh("r0")).isInstanceOfSatisfying(HarvesterException.class,
      e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.TRANSIENT));
  }

  @Test
  void missingRefreshTokenIsAuth() throws HarvesterException {
    microsoft.deviceTokens(StubMicrosoft.tokens("at1", null, "User.Read Chat.Read"));
    MicrosoftLogin login = login();
    DeviceCodeGrant grant = login.start();

    assertAuth(() -> login.await(grant), "Microsoft returned no refresh token; the app needs the offline_access permission");
  }

  @Test
  void requireScopesNamesTheAdminConsent() throws HarvesterException {
    ObjectNode withChannels = microsoft.config();
    withChannels.putArray("channels").add("Eng");
    TeamsConfig config = config(withChannels);
    Tokens tokens = new Tokens("at", "rt", Set.of("user.read", "chat.read", "team.readbasic.all", "channel.readbasic.all"));

    assertAuth(() -> MicrosoftLogin.requireScopes(tokens, config),
      "Microsoft did not grant ChannelMessage.Read.All; sign in again with 'pieria source authorize'"
        + " (reading channels needs an admin to consent to ChannelMessage.Read.All)");
  }

  @Test
  void requireScopesNamesTheAdminScopeWhenSeveralAreMissing() throws HarvesterException {
    ObjectNode withChannels = microsoft.config();
    withChannels.putArray("channels").add("Eng");
    TeamsConfig config = config(withChannels);

    assertAuth(() -> MicrosoftLogin.requireScopes(new Tokens("at", "rt", Set.of("user.read", "chat.read")), config),
      "Microsoft did not grant ChannelMessage.Read.All");
  }

  @Test
  void requireScopesNamesTheFirstMissingScope() throws HarvesterException {
    TeamsConfig config = config(microsoft.config());

    assertAuth(() -> MicrosoftLogin.requireScopes(new Tokens("at", "rt", Set.of("user.read")), config),
      "Microsoft did not grant Chat.Read; sign in again with 'pieria source authorize'");
    MicrosoftLogin.requireScopes(new Tokens("at", "rt", Set.of("user.read", "chat.read")), config);
  }

  @Test
  void tokensStayOutOfToString() {
    assertThat(new Tokens("access-secret", "refresh-secret", Set.of("chat.read")).toString())
      .doesNotContain("access-secret").doesNotContain("refresh-secret");
    assertThat(new DeviceCodeGrant(StubMicrosoft.DEVICE_CODE, "ABCD", "https://microsoft.com/devicelogin", null, 900, 5).toString())
      .doesNotContain(StubMicrosoft.DEVICE_CODE).contains("ABCD");
  }
}
