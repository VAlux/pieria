package dev.alvo.pieria.domain.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The three times a memory can carry, and which one to use for what.
 *
 * <table>
 *   <caption>Memory time fields</caption>
 *   <tr><th>Field</th><th>Means</th><th>Set by</th></tr>
 *   <tr><td>{@code createdAt}</td><td>when Pieria <em>stored</em> it</td><td>the store</td></tr>
 *   <tr><td>{@code stated_at}</td><td>when the source turn was <em>spoken</em></td><td>ingestion</td></tr>
 *   <tr><td>{@code occurred_at}</td><td>when the event <em>happened</em></td><td>extraction, for events</td></tr>
 * </table>
 *
 * <p>They diverge whenever a transcript is replayed or back-filled: a 2023 conversation ingested
 * today has a 2026 {@code createdAt} and a 2023 {@code stated_at}. Picking the wrong one is silently
 * wrong rather than visibly broken, so the two intents have separate accessors:
 *
 * <ul>
 *   <li>{@link #knowledgeTime} — <em>how current is this claim?</em> Answered by when it was said,
 *       because that is when the knowledge was acquired. Drives supersession and recency ranking.</li>
 *   <li>{@link #anchor} — <em>what is a relative phrase in this text relative to?</em> Answered by
 *       when the thing happened if known, else when it was said. Drives temporal-fact resolution.</li>
 * </ul>
 *
 * <p>Payload dates are read with a dependency-free regex so this stays a plain domain helper; both
 * a full ISO instant and a bare {@code yyyy-MM-dd} are accepted.
 */
public final class MemoryTimes {

  /** Payload key: when the turn that produced this memory was spoken. */
  public static final String STATED_AT = "stated_at";

  /** Payload key: when an event actually happened, which need not be when it was mentioned. */
  public static final String OCCURRED_AT = "occurred_at";

  private MemoryTimes() {
  }

  /**
   * How current a memory's claim is: when it was stated, falling back to when it was stored.
   *
   * <p>The fallback keeps memories written before {@code stated_at} existed — and those added
   * directly rather than extracted from a transcript — orderable against everything else.
   */
  public static Instant knowledgeTime(Memory memory) {
    if (memory == null) {
      return null;
    }
    Instant stated = instantField(memory.payload(), STATED_AT);
    return stated != null ? stated : memory.createdAt();
  }

  /**
   * The date a relative phrase in this memory's text is relative to, or {@code null} when the memory
   * records none.
   *
   * <p>{@code occurred_at} wins because when a thing happened beats when it was mentioned.
   * {@code createdAt} is deliberately <strong>not</strong> a fallback here: resolving "next month"
   * against the ingest date would produce a confident wrong answer for any back-filled transcript,
   * and saying "unresolvable" is better than that.
   */
  public static LocalDate anchor(Memory memory) {
    if (memory == null) {
      return null;
    }
    LocalDate occurred = dateField(memory.payload(), OCCURRED_AT);
    return occurred != null ? occurred : dateField(memory.payload(), STATED_AT);
  }

  /** A payload date field as a UTC date, or {@code null} when absent or unparseable. */
  public static LocalDate dateField(String payload, String field) {
    String value = rawField(payload, field);
    if (value == null || value.length() < 10) {
      return null;
    }
    try {
      return LocalDate.parse(value.substring(0, 10));
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** A payload date field as an instant; a bare date is taken as its UTC start of day. */
  private static Instant instantField(String payload, String field) {
    String value = rawField(payload, field);
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      LocalDate date = dateField(payload, field);
      return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
  }

  private static String rawField(String payload, String field) {
    if (payload == null || field == null) {
      return null;
    }
    Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"")
      .matcher(payload);
    return matcher.find() ? matcher.group(1).trim() : null;
  }
}
