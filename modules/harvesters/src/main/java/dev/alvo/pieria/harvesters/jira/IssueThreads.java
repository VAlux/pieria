package dev.alvo.pieria.harvesters.jira;

import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;
import dev.alvo.pieria.harvesters.html.HtmlToMarkdown;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a Jira issue into a thread: the description, then the comments, oldest first. The issue's
 * fields go in a header on the description, because the daemon extracts only turn text. A new
 * comment only appends a turn, so the daemon extracts just that comment.
 */
public final class IssueThreads {

  public static final String FIELDS =
    "summary,description,status,issuetype,priority,assignee,reporter,creator,created,updated,labels,components,fixVersions,resolution,project";
  private static final DateTimeFormatter JIRA_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
  private static final String UNKNOWN = "unknown";

  private IssueThreads() {
  }

  public static HarvestRecord.ConversationThread build(String baseUrl, JsonNode issue, List<JsonNode> comments) {
    String key = issue.path("key").asString();
    JsonNode fields = issue.path("fields");
    String summary = text(fields.path("summary"));
    Instant created = time(text(fields.path("created")));
    String title = key + ": " + summary;
    if (title.length() > ProtocolLimits.MAX_TITLE_CHARS) {
      title = title.substring(0, ProtocolLimits.MAX_TITLE_CHARS);
    }
    List<HarvestRecord.Turn> turns = new ArrayList<>();
    Budget budget = new Budget();
    String header = header(fields);
    String description = description(baseUrl, issue);
    String opening = description.isEmpty() ? header : header.isEmpty() ? description : header + "\n\n" + description;
    if (opening.isEmpty()) {
      opening = summary.isEmpty() ? key : summary;
    }
    String author = name(fields.path("reporter"));
    if (author == null) {
      author = name(fields.path("creator"));
    }
    turns.add(new HarvestRecord.Turn("description", author == null ? UNKNOWN : author, created == null ? Instant.EPOCH : created,
      budget.take(opening)));
    boolean truncated = false;
    for (Comment comment : sorted(baseUrl, comments, created)) {
      if (turns.size() >= ProtocolLimits.MAX_TURNS || budget.spent()) {
        truncated = true;
        break;
      }
      turns.add(new HarvestRecord.Turn("comment:" + comment.id(), comment.author(), comment.created(), budget.take(comment.text())));
    }
    if (truncated) {
      System.err.println("jira: " + key + " truncated to " + turns.size() + " turns");
    }
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("key", key);
    putIfPresent(metadata, "project", text(fields.path("project").path("key")));
    putIfPresent(metadata, "type", text(fields.path("issuetype").path("name")));
    putIfPresent(metadata, "status", text(fields.path("status").path("name")));
    return new HarvestRecord.ConversationThread(issue.path("id").asString(), title, baseUrl + "/browse/" + key, turns, metadata);
  }

  /** Jira's {@code 2026-10-01T10:00:00.000+0000}, or an ISO time; null when neither. */
  public static Instant time(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value, JIRA_TIME).toInstant();
    } catch (DateTimeParseException e) {
      try {
        return OffsetDateTime.parse(value).toInstant();
      } catch (DateTimeParseException ignored) {
        return null;
      }
    }
  }

  private record Comment(String id, String author, Instant created, String text) {
  }

  private static List<Comment> sorted(String baseUrl, List<JsonNode> comments, Instant fallback) {
    List<Comment> result = new ArrayList<>();
    for (JsonNode node : comments) {
      String text = markdown(baseUrl, node.path("renderedBody"));
      if (text.isEmpty() && node.path("body").isString()) {
        text = node.path("body").asString().strip();
      }
      if (text.isEmpty()) {
        continue;
      }
      String author = name(node.path("author"));
      Instant created = time(text(node.path("created")));
      result.add(new Comment(node.path("id").asString(), author == null ? UNKNOWN : author,
        created == null ? (fallback == null ? Instant.EPOCH : fallback) : created, text));
    }
    result.sort(Comparator.comparing(Comment::created).thenComparing(Comment::id, IssueThreads::compareIds));
    return result;
  }

  /** Numeric ids compare as numbers, so comment 10 follows comment 9. */
  private static int compareIds(String a, String b) {
    if (a.matches("\\d{1,18}") && b.matches("\\d{1,18}")) {
      return Long.compare(Long.parseLong(a), Long.parseLong(b));
    }
    return a.compareTo(b);
  }

  private static String header(JsonNode fields) {
    List<String> parts = new ArrayList<>();
    part(parts, "Type", text(fields.path("issuetype").path("name")));
    part(parts, "Status", text(fields.path("status").path("name")));
    part(parts, "Priority", text(fields.path("priority").path("name")));
    part(parts, "Assignee", name(fields.path("assignee")));
    part(parts, "Resolution", text(fields.path("resolution").path("name")));
    part(parts, "Labels", joined(fields.path("labels"), false));
    part(parts, "Components", joined(fields.path("components"), true));
    part(parts, "Fix versions", joined(fields.path("fixVersions"), true));
    return String.join(" · ", parts);
  }

  private static String description(String baseUrl, JsonNode issue) {
    String rendered = markdown(baseUrl, issue.path("renderedFields").path("description"));
    if (!rendered.isEmpty()) {
      return rendered;
    }
    // Data Center sends wiki markup as a string; Cloud sends an ADF object, which this doesn't read.
    JsonNode raw = issue.path("fields").path("description");
    return raw.isString() ? raw.asString().strip() : "";
  }

  private static String markdown(String baseUrl, JsonNode html) {
    if (!html.isString() || html.asString().isBlank()) {
      return "";
    }
    return HtmlToMarkdown.convert(Jsoup.parseBodyFragment(html.asString(), baseUrl));
  }

  private static void part(List<String> parts, String label, String value) {
    if (value != null && !value.isEmpty()) {
      parts.add(label + ": " + value);
    }
  }

  private static String joined(JsonNode array, boolean named) {
    if (!array.isArray()) {
      return null;
    }
    List<String> values = new ArrayList<>();
    for (JsonNode element : array) {
      String value = named ? text(element.path("name")) : text(element);
      if (!value.isEmpty()) {
        values.add(value);
      }
    }
    return String.join(", ", values);
  }

  private static String name(JsonNode user) {
    String name = text(user.path("displayName"));
    return name.isEmpty() ? null : truncate(name, ProtocolLimits.MAX_AUTHOR_CHARS);
  }

  private static String text(JsonNode node) {
    return node.isString() ? node.asString().strip() : "";
  }

  private static String truncate(String value, int max) {
    return value.length() > max ? value.substring(0, max) : value;
  }

  private static void putIfPresent(Map<String, String> metadata, String key, String value) {
    if (value != null && !value.isEmpty()) {
      metadata.put(key, truncate(value, ProtocolLimits.MAX_METADATA_VALUE_CHARS));
    }
  }

  /** What is left of a thread's text allowance. */
  private static final class Budget {
    private long remaining = ProtocolLimits.MAX_TEXT_CHARS;

    String take(String text) {
      String kept = text.length() > remaining ? text.substring(0, (int) remaining) : text;
      remaining -= kept.length();
      return kept;
    }

    boolean spent() {
      return remaining <= 0;
    }
  }
}
