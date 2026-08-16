package dev.alvo.pieria.ingestion;


import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.tools.RelativeDates;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
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
 *   <li>Resolve unambiguous relative dates against <em>when that message was spoken</em> — its own
 *       {@code createdAt} when it has one, else the request timestamp. No model call is made here;
 *       this is the deterministic half of "temporal arithmetic in Java, not the model". Anything
 *       genuinely fuzzy ("last summer", "a while back") is left untouched for the model.</li>
 *   <li>Keep raw message text otherwise intact.</li>
 * </ul>
 *
 * <h2>Relative dates are replaced, never annotated</h2>
 * Every resolved reference is <em>substituted at the same granularity the speaker used</em>:
 * "yesterday" becomes an ISO date, "next month" becomes {@code "June 2023"}, "last week" becomes
 * {@code "the week of 2023-05-15"}. No precision is invented, because a month-precision phrase is
 * replaced by a month and a week-precision phrase by a week.
 *
 * <p>An earlier version instead <em>appended</em> the absolute period — {@code "next month
 * (June 2023)"} — and that failed in practice: a parenthetical is detachable, and the extractor
 * re-attached the date to a neighbouring clause, leaving "next month" anchored to nothing. Synthesis
 * then did its own arithmetic on the stray date and answered a month late. A replacement cannot come
 * apart from its referent, because the phrase <em>is</em> the date.
 *
 * <p>Periods matter more than they look: across a real multi-session corpus they outnumber
 * day-precision references roughly 2.6 to 1. Left unresolved they enter the store as permanently
 * undated facts ("planning a camping trip next month"), which no amount of retrieval quality can
 * repair afterwards, because the anchor date is gone by then.
 */
@Component
public class TranscriptNormalizer {

  private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

  // Word-boundary, case-insensitive matches for the three deterministic relative dates.
  private static final Pattern YESTERDAY = Pattern.compile("\\byesterday\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern TODAY = Pattern.compile("\\btoday\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern TOMORROW = Pattern.compile("\\btomorrow\\b", Pattern.CASE_INSENSITIVE);

  /**
   * Calendar periods relative to the speaking date, shared with the retrieval side so a rewritten
   * transcript and a resolved temporal fact can never disagree. Seasons are deliberately absent:
   * "last summer" is hemisphere-dependent and has no calendar definition, so it stays fuzzy and goes
   * to the model as written.
   */
  private static final Pattern RELATIVE_PERIOD = RelativeDates.PERIOD;

  /**
   * Validate, order-preserve, and date-normalize the given messages.
   *
   * @param messages    raw inbound messages (may contain invalid entries)
   * @param requestTime fallback timestamp for messages that carry no {@code createdAt} of their own;
   *                    relative dates resolve against the message's time, else this one (UTC)
   * @return a new list of valid, normalized messages in source order
   */
  public List<Message> normalize(List<Message> messages, Instant requestTime) {
    if (messages == null) {
      return List.of();
    }
    LocalDate fallback = LocalDate.ofInstant(requestTime, ZoneOffset.UTC);
    List<Message> out = new ArrayList<>(messages.size());
    for (Message message : messages) {
      if (message == null || isBlank(message.role()) || isBlank(message.content()) || isBlank(message.sessionId())) {
        continue;
      }
      // A back-filled or replayed transcript carries the time each turn was actually spoken; without
      // it "yesterday" would silently resolve to the day before the ingest, not the day before the
      // conversation. Multi-session transcripts spanning months need this per message, not per request.
      LocalDate spokenOn = message.createdAt() == null
        ? fallback
        : LocalDate.ofInstant(message.createdAt(), ZoneOffset.UTC);

      String content = resolveRelativeDates(message.content(), spokenOn);
      out.add(new Message(message.id(), message.sessionId(), message.role().trim(), content, message.createdAt()));
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
    result = RELATIVE_PERIOD.matcher(result)
      .replaceAll(match -> Matcher.quoteReplacement(resolvePeriod(match, today)));
    return result;
  }

  /**
   * The absolute period a relative one names, at the same granularity: {@code "next month"} spoken
   * on 2023-05-25 becomes {@code "June 2023"}.
   */
  private static String resolvePeriod(MatchResult match, LocalDate spokenOn) {
    return RelativeDates.period(match.group(1), match.group(2), spokenOn);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
