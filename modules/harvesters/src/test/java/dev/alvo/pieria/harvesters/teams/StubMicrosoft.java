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
  static final String ALL_SCOPES = "User.Read Chat.Read Team.ReadBasic.All Channel.ReadBasic.All ChannelMessage.Read.All";
  private volatile JsonStub.Reply refresh = tokens("at1", "r1", ALL_SCOPES);
  private final List<Chat> chats = new java.util.concurrent.CopyOnWriteArrayList<>();
  private final List<Team> teams = new java.util.concurrent.CopyOnWriteArrayList<>();
  /** How many replies a delta page expands before it links to the rest. */
  volatile int expandedReplies = 1_000;
  /** When set, the chats page links here as its next page. */
  volatile String chatsNextLink;

  StubMicrosoft() {
    stub.route(loginPath("devicecode"), request -> deviceCode);
    stub.route(loginPath("token"), request -> {
      if (form(request).get("grant_type").equals("refresh_token")) {
        return refresh;
      }
      JsonStub.Reply next = deviceTokens.size() > 1 ? deviceTokens.poll() : deviceTokens.peek();
      return next == null ? oauthError("authorization_pending", "AADSTS70016: pending") : next;
    });
    me("u1", "Me Myself");
    stub.route("/v1.0/me/chats", request -> {
      List<ObjectNode> values = new ArrayList<>();
      for (Chat chat : chats) {
        values.add(chat.json());
      }
      ObjectNode body = MAPPER.createObjectNode();
      body.putArray("value").addAll(values);
      if (chatsNextLink != null && !request.query().containsKey("$skiptoken")) {
        body.put("@odata.nextLink", chatsNextLink);
      }
      return JsonStub.Reply.json(body);
    });
    stub.route("/v1.0/me/joinedTeams", request -> {
      ObjectNode body = MAPPER.createObjectNode();
      teams.forEach(team -> body.withArray("value").addObject().put("id", team.id).put("displayName", team.name));
      body.withArray("value");
      return JsonStub.Reply.json(body);
    });
  }

  /** A chat with its messages, served filtered and ordered the way Graph does. */
  final class Chat {
    final String id;
    final String chatType;
    final String topic;
    final List<ObjectNode> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<String[]> members = new java.util.concurrent.CopyOnWriteArrayList<>();

    Chat(String id, String chatType, String topic) {
      this.id = id;
      this.chatType = chatType;
      this.topic = topic;
      stub.route("/v1.0/chats/" + id + "/messages", this::messages);
      stub.route("/v1.0/chats/" + id + "/members", request -> {
        ObjectNode body = MAPPER.createObjectNode();
        body.putArray("value");
        members.forEach(member -> body.withArray("value").addObject().put("userId", member[0]).put("displayName", member[1]));
        return JsonStub.Reply.json(body);
      });
    }

    ObjectNode message(String messageId, String author, java.time.Instant created, String html) {
      ObjectNode message = TeamsJson.message(messageId, author, created, html);
      messages.add(message);
      return message;
    }

    Chat member(String userId, String name) {
      members.add(new String[] {userId, name});
      return this;
    }

    ObjectNode json() {
      ObjectNode chat = MAPPER.createObjectNode().put("id", id).put("chatType", chatType);
      if (topic == null) {
        chat.putNull("topic");
      } else {
        chat.put("topic", topic);
      }
      messages.stream().max(java.util.Comparator.comparing(m -> java.time.Instant.parse(m.get("createdDateTime").asString())))
        .ifPresentOrElse(last -> chat.putObject("lastMessagePreview").put("id", last.get("id").asString())
          .put("createdDateTime", last.get("createdDateTime").asString()), () -> chat.putNull("lastMessagePreview"));
      return chat;
    }

    private JsonStub.Reply messages(JsonStub.Request request) {
      String orderBy = request.query().getOrDefault("$orderby", "lastModifiedDateTime desc");
      String property = orderBy.split(" ")[0];
      List<ObjectNode> matching = new ArrayList<>(messages);
      String filter = request.query().get("$filter");
      if (filter != null) {
        String[] parts = filter.split(" ");
        if (!parts[0].equals(property)) {
          return new JsonStub.Reply(400, Map.of(), "{\"error\":{\"message\":\"filter needs orderby on the same property\"}}");
        }
        java.time.Instant bound = java.time.Instant.parse(parts[2]);
        matching.removeIf(m -> {
          java.time.Instant value = java.time.Instant.parse(m.get(property).asString());
          return parts[1].equals("gt") ? !value.isAfter(bound) : !value.isBefore(bound);
        });
      }
      matching.sort(java.util.Comparator.comparing((ObjectNode m) -> java.time.Instant.parse(m.get(property).asString())).reversed());
      return page(request, "/v1.0/chats/" + id + "/messages", matching);
    }
  }

  /** A joined team and its channels. */
  final class Team {
    final String id;
    final String name;
    final List<Channel> channels = new java.util.concurrent.CopyOnWriteArrayList<>();

    Team(String id, String name) {
      this.id = id;
      this.name = name;
      stub.route("/v1.0/teams/" + id + "/channels", request -> {
        ObjectNode body = MAPPER.createObjectNode();
        body.putArray("value");
        channels.forEach(channel -> body.withArray("value").addObject().put("id", channel.id).put("displayName", channel.name));
        return JsonStub.Reply.json(body);
      });
    }

    Channel channel(String channelId, String channelName) {
      Channel channel = new Channel(this, channelId, channelName);
      channels.add(channel);
      return channel;
    }
  }

  /** A channel's posts, each with its replies, served through delta the way Graph does. */
  final class Channel {
    final Team team;
    final String id;
    final String name;
    final List<ObjectNode> posts = new java.util.concurrent.CopyOnWriteArrayList<>();
    final Map<String, List<ObjectNode>> replies = new java.util.concurrent.ConcurrentHashMap<>();

    Channel(Team team, String id, String name) {
      this.team = team;
      this.id = id;
      this.name = name;
      stub.route(base() + "/messages/delta", this::delta);
    }

    String base() {
      return "/v1.0/teams/" + team.id + "/channels/" + id;
    }

    ObjectNode post(String postId, String author, java.time.Instant created, String html) {
      ObjectNode post = TeamsJson.message(postId, author, created, html);
      posts.add(post);
      List<ObjectNode> list = new java.util.concurrent.CopyOnWriteArrayList<>();
      replies.put(postId, list);
      stub.route(base() + "/messages/" + postId + "/replies", request -> page(request, base() + "/messages/" + postId + "/replies", list));
      return post;
    }

    ObjectNode reply(String postId, String replyId, String author, java.time.Instant created, String html) {
      ObjectNode reply = TeamsJson.message(replyId, author, created, html);
      replies.get(postId).add(reply);
      return reply;
    }

    private java.time.Instant changed(ObjectNode post) {
      java.time.Instant latest = java.time.Instant.parse(post.get("lastModifiedDateTime").asString());
      for (ObjectNode reply : replies.get(post.get("id").asString())) {
        java.time.Instant modified = java.time.Instant.parse(reply.get("lastModifiedDateTime").asString());
        latest = modified.isAfter(latest) ? modified : latest;
      }
      return latest;
    }

    private JsonStub.Reply delta(JsonStub.Request request) {
      String filter = request.query().get("$filter");
      java.time.Instant bound = filter == null ? java.time.Instant.EPOCH : java.time.Instant.parse(filter.split(" ")[2]);
      List<ObjectNode> matching = new ArrayList<>();
      for (ObjectNode post : posts) {
        if (changed(post).isAfter(bound)) {
          ObjectNode copy = post.deepCopy();
          List<ObjectNode> all = replies.get(post.get("id").asString());
          copy.putArray("replies").addAll(all.subList(0, Math.min(expandedReplies, all.size())));
          if (all.size() > expandedReplies) {
            copy.put("replies@odata.nextLink", baseUrl() + base() + "/messages/" + post.get("id").asString() + "/replies?$skiptoken=x");
          }
          matching.add(copy);
        }
      }
      JsonStub.Reply reply = page(request, base() + "/messages/delta", matching);
      return reply;
    }
  }

  Chat chat(String id, String chatType, String topic) {
    Chat chat = new Chat(id, chatType, topic);
    chats.add(chat);
    return chat;
  }

  Team team(String id, String name) {
    Team team = new Team(id, name);
    teams.add(team);
    return team;
  }

  /** One page of {@code rows}, {@code $top} long, linking to the next by offset and to a delta link at the end. */
  private JsonStub.Reply page(JsonStub.Request request, String path, List<ObjectNode> rows) {
    int top = Integer.parseInt(request.query().getOrDefault("$top", "50"));
    int skip = Integer.parseInt(request.query().getOrDefault("$skip", "0"));
    ObjectNode body = MAPPER.createObjectNode();
    body.putArray("value").addAll(rows.subList(Math.min(skip, rows.size()), Math.min(rows.size(), skip + top)));
    if (skip + top < rows.size()) {
      StringBuilder link = new StringBuilder(baseUrl()).append(path).append("?$top=").append(top).append("&$skip=").append(skip + top);
      request.query().forEach((name, value) -> {
        if (!name.equals("$top") && !name.equals("$skip") && !name.equals("$skiptoken")) {
          link.append('&').append(name).append('=').append(dev.alvo.pieria.harvesters.http.JsonTransport.encode(value));
        }
      });
      body.put("@odata.nextLink", link.toString());
    } else if (path.endsWith("/delta")) {
      body.put("@odata.deltaLink", baseUrl() + path + "?$deltatoken=end");
    }
    return JsonStub.Reply.json(body);
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

  /** What Entra answers a refresh that asks for a permission nobody consented to. */
  static JsonStub.Reply consentRequired() {
    ObjectNode body = MAPPER.createObjectNode().put("error", "invalid_grant")
      .put("error_description", "AADSTS65001: The user or administrator has not consented to use the application. Send an interactive"
        + " authorization request for this user and resource.\r\nTrace ID: 1")
      .put("suberror", "consent_required");
    body.putArray("error_codes").add(65001);
    return new JsonStub.Reply(400, Map.of("Content-Type", "application/json"), body.toString());
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
