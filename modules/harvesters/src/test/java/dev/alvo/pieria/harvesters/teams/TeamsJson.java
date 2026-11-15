package dev.alvo.pieria.harvesters.teams;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

/** Graph-shaped messages for tests. */
final class TeamsJson {

  static final ObjectMapper MAPPER = new ObjectMapper();

  private TeamsJson() {
  }

  static ObjectNode message(String id, String author, Instant created, String html) {
    ObjectNode message = MAPPER.createObjectNode().put("id", id).put("messageType", "message")
      .put("createdDateTime", created.toString()).put("lastModifiedDateTime", created.toString());
    message.putNull("deletedDateTime");
    message.putObject("from").putObject("user").put("id", "id-" + author).put("displayName", author);
    message.putObject("body").put("contentType", "html").put("content", html);
    return message;
  }
}
