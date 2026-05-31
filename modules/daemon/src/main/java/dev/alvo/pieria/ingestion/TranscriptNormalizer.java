package dev.alvo.pieria.ingestion;


import dev.alvo.pieria.domain.memory.Message;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Normalizes and validates raw inbound conversation messages before chunking/extraction.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Drop messages whose {@code role}, {@code content}, or {@code sessionId} is blank. Invalid
 *       messages are simply skipped (not rejected with an exception) so a single malformed line
 *       cannot fail an entire ingest; the surviving messages keep their relative source order.</li>
 *   <li>Preserve source order. The position of a normalized message in the returned list is its
 *       stable provenance index (the {@code [n]} rendered by {@link #render}).</li>
 *   <li>Resolve only obvious, unambiguous relative dates ("yesterday"/"today"/"tomorrow") against
 *       the request timestamp, rewriting them to absolute ISO dates. Anything fuzzier is left
 *       untouched for the model. No model call is made here.</li>
 *   <li>Keep raw message text otherwise intact.</li>
 * </ul>
 */
@Component
public class TranscriptNormalizer {

  private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

  // Word-boundary, case-insensitive matches for the three deterministic relative dates.
  private static final Pattern YESTERDAY = Pattern.compile("\\byesterday\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern TODAY = Pattern.compile("\\btoday\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern TOMORROW = Pattern.compile("\\btomorrow\\b", Pattern.CASE_INSENSITIVE);

  /**
   * Validate, order-preserve, and date-normalize the given messages.
   *
   * @param messages    raw inbound messages (may contain invalid entries)
   * @param requestTime the ingest request timestamp; relative dates resolve against this (UTC)
   * @return a new list of valid, normalized messages in source order
   */
  public List<Message> normalize(List<Message> messages, Instant requestTime) {
    if (messages == null) {
      return List.of();
    }
    LocalDate today = LocalDate.ofInstant(requestTime, ZoneOffset.UTC);
    List<Message> out = new ArrayList<>(messages.size());
    for (Message m : messages) {
      if (m == null || isBlank(m.role()) || isBlank(m.content()) || isBlank(m.sessionId())) {
        continue;
      }
      String content = resolveRelativeDates(m.content(), today);
      out.add(new Message(m.id(), m.sessionId(), m.role().trim(), content, m.createdAt()));
    }
    return out;
  }

  /**
   * Render a role-labeled transcript with provenance line indices, e.g.
   * {@code [0] user: hello}. Each message occupies one labeled line; the index is the message's
   * position in {@code messages}. Suitable for {@code Chunk.transcript}.
   */
  public String render(List<Message> messages) {
    return render(messages, 0);
  }

  /**
   * Render with an explicit starting line index so a chunk's transcript reports absolute message
   * indices (matching the normalized list) rather than chunk-relative ones.
   */
  public String render(List<Message> messages, int startIndex) {
    StringBuilder sb = new StringBuilder();
    int idx = startIndex;
    for (Message m : messages) {
      if (idx > startIndex) {
        sb.append('\n');
      }
      sb.append('[').append(idx).append("] ").append(m.role()).append(": ").append(m.content());
      idx++;
    }
    return sb.toString();
  }

  private String resolveRelativeDates(String content, LocalDate today) {
    String result = content;
    result = YESTERDAY.matcher(result).replaceAll(today.minusDays(1).format(ISO_DATE));
    result = TODAY.matcher(result).replaceAll(today.format(ISO_DATE));
    result = TOMORROW.matcher(result).replaceAll(today.plusDays(1).format(ISO_DATE));
    return result;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
