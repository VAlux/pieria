package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvesters.JsonStub;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;

/** Microsoft's sign-in and Graph on one localhost stub, so loginUrl and graphUrl are both its address. */
final class StubMicrosoft implements AutoCloseable {

  static final String CLIENT_ID = "11111111-2222-3333-4444-555555555555";
  static final String DEVICE_CODE = "DC-secret-device-code";
  static final String TENANT = "organizations";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final JsonStub stub = JsonStub.start();
  private final ConcurrentLinkedDeque<JsonStub.Reply> deviceTokens = new ConcurrentLinkedDeque<>();
  private volatile JsonStub.Reply deviceCode = deviceCodeReply(900, 0);
  private volatile JsonStub.Reply refresh = tokens("at1", "r1", "User.Read Chat.Read");

  StubMicrosoft() {
    stub.route(loginPath("devicecode"), request -> deviceCode);
    stub.route(loginPath("token"), request -> {
      if (form(request).get("grant_type").equals("refresh_token")) {
        return refresh;
      }
      JsonStub.Reply next = deviceTokens.size() > 1 ? deviceTokens.poll() : deviceTokens.peek();
      return next == null ? oauthError("authorization_pending", "AADSTS70016: pending") : next;
    });
    me("u1", "Ada Lovelace");
  }

  static String loginPath(String endpoint) {
    return "/" + TENANT + "/oauth2/v2.0/" + endpoint;
  }

  String baseUrl() {
    return stub.baseUrl();
  }

  ObjectNode config() {
    return MAPPER.createObjectNode().put("clientId", CLIENT_ID).put("loginUrl", baseUrl()).put("graphUrl", baseUrl());
  }

  static JsonStub.Reply deviceCodeReply(int expiresIn, Integer interval) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("device_code", DEVICE_CODE);
    body.put("user_code", "ABCD-EFGH");
    body.put("verification_uri", "https://microsoft.com/devicelogin");
    body.put("expires_in", expiresIn);
    if (interval != null) {
      body.put("interval", interval);
    }
    body.put("message", "To sign in, use a web browser to open the page https://microsoft.com/devicelogin and enter the code ABCD-EFGH.");
    return JsonStub.Reply.json(body);
  }

  static JsonStub.Reply tokens(String access, String refreshToken, String scope) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("token_type", "Bearer");
    body.put("access_token", access);
    if (refreshToken != null) {
      body.put("refresh_token", refreshToken);
    }
    body.put("scope", scope);
    body.put("expires_in", 3600);
    return JsonStub.Reply.json(body);
  }

  static JsonStub.Reply oauthError(String error, String description) {
    return new JsonStub.Reply(400, Map.of("Content-Type", "application/json"),
      MAPPER.createObjectNode().put("error", error).put("error_description", description).toString());
  }

  StubMicrosoft deviceCode(JsonStub.Reply reply) {
    deviceCode = reply;
    return this;
  }

  /** Token replies for the device-code grant, in order; the last one repeats. */
  StubMicrosoft deviceTokens(JsonStub.Reply... replies) {
    deviceTokens.clear();
    deviceTokens.addAll(List.of(replies));
    return this;
  }

  StubMicrosoft refresh(JsonStub.Reply reply) {
    refresh = reply;
    return this;
  }

  StubMicrosoft me(String id, String name) {
    return graph("/v1.0/me", request -> JsonStub.Reply.json(Map.of("id", id, "displayName", name)));
  }

  StubMicrosoft graph(String path, Function<JsonStub.Request, JsonStub.Reply> handler) {
    stub.route(path, handler);
    return this;
  }

  List<JsonStub.Request> requests(String path) {
    return stub.requests(path);
  }

  List<Map<String, String>> tokenForms() {
    List<Map<String, String>> forms = new ArrayList<>();
    stub.requests(loginPath("token")).forEach(request -> forms.add(form(request)));
    return forms;
  }

  static Map<String, String> form(JsonStub.Request request) {
    Map<String, String> form = new LinkedHashMap<>();
    for (String pair : request.body().split("&")) {
      int equals = pair.indexOf('=');
      if (equals > 0) {
        form.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
      }
    }
    return form;
  }

  @Override
  public void close() {
    stub.close();
  }
}
