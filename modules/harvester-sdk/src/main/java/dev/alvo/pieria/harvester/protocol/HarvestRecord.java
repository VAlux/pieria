package dev.alvo.pieria.harvester.protocol;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One line of harvester output. Every record validates itself on construction, so a harvester can't
 * emit, and a reader can't accept, a record that breaks the protocol.
 */
public sealed interface HarvestRecord {

  /** One unit at the source, with an id that stays the same across runs. */
  sealed interface Item extends HarvestRecord {
    String id();
  }

  /** A page or other single body of text. Wire type {@code document}. */
  record Document(String id, String version, String title, String url, String author, Instant updatedAt,
                  DocumentFormat format, String text, Map<String, String> metadata) implements Item {
    public Document {
      requireId(id);
      optionalBounded("version", version, ProtocolLimits.MAX_ID_CHARS);
      requireBounded("title", title, ProtocolLimits.MAX_TITLE_CHARS);
      requireUrl(url);
      optionalBounded("author", author, ProtocolLimits.MAX_AUTHOR_CHARS);
      require(updatedAt != null, "document updatedAt is required");
      require(format != null, "document format is required");
      requireBounded("text", text, ProtocolLimits.MAX_TEXT_CHARS);
      metadata = normalizedMetadata(metadata);
    }
  }

  /** A conversation, such as a ticket with comments or a chat thread. Wire type {@code thread}. */
  record ConversationThread(String id, String title, String url, List<Turn> turns,
                            Map<String, String> metadata) implements Item {
    public ConversationThread {
      requireId(id);
      requireBounded("title", title, ProtocolLimits.MAX_TITLE_CHARS);
      requireUrl(url);
      require(turns != null && !turns.isEmpty(), "thread needs at least one turn");
      require(turns.size() <= ProtocolLimits.MAX_TURNS, "thread has too many turns");
      turns = List.copyOf(turns);
      Set<String> turnIds = new HashSet<>();
      long chars = 0;
      for (Turn turn : turns) {
        require(turnIds.add(turn.id()), "duplicate turn id: " + turn.id());
        chars += turn.text().length();
      }
      require(chars <= ProtocolLimits.MAX_TEXT_CHARS, "thread text is over the limit");
      metadata = normalizedMetadata(metadata);
    }
  }

  /** One message in a thread. */
  record Turn(String id, String author, Instant timestamp, String text) {
    public Turn {
      requireId(id);
      require(author != null && !author.isBlank(), "turn author is required");
      requireBounded("author", author, ProtocolLimits.MAX_AUTHOR_CHARS);
      require(timestamp != null, "turn timestamp is required");
      requireBounded("text", text, ProtocolLimits.MAX_TEXT_CHARS);
    }
  }

  /** The source says this item is gone. */
  record Tombstone(String id) implements HarvestRecord {
    public Tombstone {
      requireId(id);
    }
  }

  /** These items still exist. Used by sources with no delete feed, ahead of a {@link Sweep}. */
  record Seen(List<String> ids) implements HarvestRecord {
    public Seen {
      require(ids != null && !ids.isEmpty(), "seen needs at least one id");
      require(ids.size() <= ProtocolLimits.MAX_SEEN_IDS, "seen has too many ids");
      ids.forEach(HarvestRecord::requireId);
      ids = List.copyOf(ids);
    }
  }

  /**
   * Every live item was reported in this run, so anything else is gone. A run that was resumed or cut
   * short by a limit has not seen everything and must never emit this.
   */
  record Sweep() implements HarvestRecord {
  }

  /** Everything before this record is complete. The cursor is returned on the next run. */
  record Checkpoint(JsonNode cursor) implements HarvestRecord {
    public Checkpoint {
      require(cursor != null && !cursor.isNull(), "checkpoint cursor is required");
      require(cursor.toString().length() <= ProtocolLimits.MAX_CURSOR_CHARS, "checkpoint cursor is over the limit");
    }
  }

  /** How far the run has got. {@code total} may be null when unknown. */
  record Progress(long done, Long total) implements HarvestRecord {
    public Progress {
      require(done >= 0, "progress done must not be negative");
      require(total == null || total >= 0, "progress total must not be negative");
    }
  }

  /** Replaces a stored secret, for example a rotated OAuth refresh token. */
  record SecretUpdate(String name, String value) implements HarvestRecord {
    public SecretUpdate {
      require(name != null && !name.isBlank(), "secret name is required");
      require(value != null && !value.isBlank(), "secret value is required");
    }

    @Override
    public String toString() {
      return "SecretUpdate[name=" + name + "]";
    }
  }

  /** The run failed. Wire type {@code error}. {@code retryAfterSeconds} may be null. */
  record Failure(ErrorClass errorClass, String message, Long retryAfterSeconds) implements HarvestRecord {
    public Failure {
      require(errorClass != null, "error class is required");
      require(message != null, "error message is required");
      require(retryAfterSeconds == null || retryAfterSeconds >= 0, "retryAfter must not be negative");
      if (message.length() > ProtocolLimits.MAX_ERROR_CHARS) {
        message = message.substring(0, ProtocolLimits.MAX_ERROR_CHARS);
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new ProtocolViolation(message);
    }
  }

  private static void requireId(String id) {
    require(id != null && !id.isBlank(), "id is required");
    require(id.length() <= ProtocolLimits.MAX_ID_CHARS, "id is over the limit");
  }

  private static void requireUrl(String url) {
    require(url != null && !url.isBlank(), "url is required");
    require(url.length() <= ProtocolLimits.MAX_URL_CHARS, "url is over the limit");
  }

  private static void requireBounded(String field, String value, int maxChars) {
    require(value != null, field + " is required");
    optionalBounded(field, value, maxChars);
  }

  private static void optionalBounded(String field, String value, int maxChars) {
    require(value == null || value.length() <= maxChars, field + " is over the limit");
  }

  private static Map<String, String> normalizedMetadata(Map<String, String> metadata) {
    if (metadata == null) {
      return Map.of();
    }
    require(metadata.size() <= ProtocolLimits.MAX_METADATA_ENTRIES, "too many metadata entries");
    metadata.forEach((key, value) -> {
      requireBounded("metadata key", key, ProtocolLimits.MAX_ID_CHARS);
      requireBounded("metadata value", value, ProtocolLimits.MAX_METADATA_VALUE_CHARS);
    });
    return Map.copyOf(metadata);
  }
}
