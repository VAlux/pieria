package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;
import dev.alvo.pieria.harvesters.ThreadBudget;
import dev.alvo.pieria.harvesters.html.HtmlToMarkdown;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reads Graph {@code chatMessage} objects into turns. */
final class TeamsMessages {

  /** Replies that carry no knowledge: acknowledgements and reactions typed as text. */
  private static final Set<String> TRIVIAL = Set.of("ok", "okay", "k", "kk", "thanks", "thank you", "thx", "ty", "np", "yes", "no",
    "yep", "nope", "sure", "lol", "cool", "great", "nice", "done", "1");
  /** Graph's message ids are epoch milliseconds, so a shorter id is an older one. */
  private static final Comparator<JsonNode> ORDER = Comparator.comparing(TeamsMessages::created)
    .thenComparingInt(message -> message.path("id").asString("").length())
    .thenComparing(message -> message.path("id").asString(""));

  private TeamsMessages() {
  }

  static Instant created(JsonNode message) {
    return Instant.parse(message.path("createdDateTime").asString());
  }

  static Instant modified(JsonNode message) {
    JsonNode modified = message.path("lastModifiedDateTime");
    return modified.isString() && !modified.asString().isBlank() ? Instant.parse(modified.asString()) : created(message);
  }

  static String author(JsonNode message) {
    JsonNode from = message.path("from");
    String name = displayName(from.path("user"));
    if (name == null) {
      name = displayName(from.path("application"));
    }
    if (name == null) {
      name = "unknown";
    }
    return name.length() > ProtocolLimits.MAX_AUTHOR_CHARS ? name.substring(0, ProtocolLimits.MAX_AUTHOR_CHARS) : name;
  }

  private static String displayName(JsonNode identity) {
    JsonNode name = identity.path("displayName");
    return name.isString() && !name.asString().isBlank() ? name.asString().strip() : null;
  }

  static String text(JsonNode message) {
    JsonNode body = message.path("body");
    String content = body.path("content").asString("");
    if (body.path("contentType").asString("").equalsIgnoreCase("html")) {
      return HtmlToMarkdown.convert(Jsoup.parseBodyFragment(content)).strip();
    }
    return content.strip();
  }

  /** System events, deleted messages and empty ones are never turns. */
  static boolean skipped(JsonNode message) {
    JsonNode deleted = message.get("deletedDateTime");
    return !message.path("messageType").asString("").equals("message")
      || (deleted != null && !deleted.isNull())
      || text(message).isBlank();
  }

  static boolean trivial(String text) {
    String normalized = text.toLowerCase(Locale.ROOT).codePoints()
      .filter(c -> Character.isLetterOrDigit(c) || c == ' ')
      .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
      .toString().replaceAll(" +", " ").strip();
    return normalized.isEmpty() || TRIVIAL.contains(normalized);
  }

  /**
   * The messages as turns, oldest first. Skipped messages are dropped, and so are trivial ones apart
   * from the first message when {@code keepFirst} is set. The oldest turns are kept within the
   * protocol's turn and text limits.
   */
  static List<HarvestRecord.Turn> turns(List<JsonNode> messages, boolean keepFirst) {
    List<JsonNode> ordered = new ArrayList<>(messages);
    ordered.sort(ORDER);
    ThreadBudget budget = new ThreadBudget();
    List<HarvestRecord.Turn> turns = new ArrayList<>();
    for (int i = 0; i < ordered.size(); i++) {
      JsonNode message = ordered.get(i);
      if (skipped(message)) {
        continue;
      }
      String text = text(message);
      if (trivial(text) && !(keepFirst && i == 0)) {
        continue;
      }
      if (turns.size() >= ProtocolLimits.MAX_TURNS || budget.spent()) {
        break;
      }
      turns.add(new HarvestRecord.Turn(message.path("id").asString(), author(message), created(message), budget.take(text)));
    }
    return turns;
  }
}
