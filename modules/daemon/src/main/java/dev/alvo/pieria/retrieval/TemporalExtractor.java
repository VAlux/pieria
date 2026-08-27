package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryTimes;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.retrieval.model.TemporalFact;
import dev.alvo.pieria.tools.RelativeDates;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic temporal extraction for the synthesis prompt.
 *
 * <p>All date math and durations are computed here in {@code java.time} and handed to synthesis as
 * ready-made {@link TemporalFact}s. The model is <strong>never</strong> asked to do arithmetic,
 * because models are unreliable at it.
 *
 * <p>This is a plain, side-effect-free class (no I/O, no Spring). Dates are interpreted as UTC
 * {@link LocalDate}; the "today" anchor is {@code requestTime} converted to its UTC date.
 *
 * <h2>Supported patterns</h2>
 * Detection is case-insensitive. Each pattern that matches contributes one (or more) facts.
 * Unparseable fragments are skipped; the method never throws.
 *
 * <ol>
 *   <li><b>Two ISO dates with a span phrase</b> ("between A and B", "from A to B", "A to B",
 *       "how long ... A ... B"): emits the day count between them. Example fact —
 *       {@code "days between 2026-01-01 and 2026-01-10" : "9 days"}.</li>
 *   <li><b>Absolute ISO date</b> ({@code yyyy-MM-dd}) with no span partner: emits days from that
 *       date to today, signed by past/future. Example —
 *       {@code "days from 2026-01-01 to today (2026-05-23)" : "142 days ago"} or, for a future
 *       date, {@code "... : in 8 days"}; the request date itself yields {@code "today"}.</li>
 *   <li><b>today / yesterday / tomorrow</b>: resolves to the absolute date. Example —
 *       {@code "yesterday resolves to" : "2026-05-22"}.</li>
 *   <li><b>N day(s)/week(s)/month(s) ago</b>: resolves the absolute date. Example —
 *       {@code "3 days ago resolves to" : "2026-05-20"}.</li>
 *   <li><b>in N day(s)/week(s)/month(s)</b>: resolves the absolute future date. Example —
 *       {@code "in 2 weeks resolves to" : "2026-06-06"}.</li>
 *   <li><b>last week / next week</b>: resolves to the date 7 days before/after today. Example —
 *       {@code "last week resolves to" : "2026-05-16"}.</li>
 *   <li><b>Event memory {@code occurred_at}</b> (only {@link MemoryType#EVENT}): when the query
 *       asks "how long ago" / "days since" / "when", emits days since each event date. Example —
 *       {@code "days since event (2026-05-01): <content>" : "22 days ago"}.</li>
 *   <li><b>Residual relative references in memory content</b> — see below.</li>
 * </ol>
 *
 * <h2>Residual relative references</h2>
 * Ingestion rewrites relative dates out of a transcript while it still knows when each turn was
 * spoken, but some survive into stored memories: text remembered directly through {@code
 * POST /memories} never passes through that rewrite, and references with no calendar definition
 * ("last summer", "a while back") are deliberately left alone.
 *
 * <p>Left unremarked, synthesis treats them as arithmetic it is entitled to do — one observed answer
 * combined a stored "June 2023" with a stray "next month" and confidently reported July. So each
 * residual reference becomes a fact of its own:
 * <ul>
 *   <li>with a trustworthy anchor (an {@code occurred_at} recording when the content was true), the
 *       reference is <em>resolved</em>: {@code "\"next month\" in the memory dated 2023-05-25
 *       resolves to" : "June 2023"};</li>
 *   <li>without one, it is <em>flagged</em>: {@code "\"next month\" in a memory has no recorded date
 *       to anchor it" : "leave it unresolved — do not infer a date"}.</li>
 * </ul>
 *
 * <p>{@code Memory.createdAt} is deliberately <strong>not</strong> used as the anchor: it records
 * when Pieria stored a fact, not when the fact was true, so a back-filled transcript would resolve
 * every reference to the ingest date and be silently, confidently wrong. Flagging is the honest
 * fallback.
 *
 * <p>Pluralization is handled: {@code "1 day"}, not {@code "1 days"}. JSON {@code occurred_at} is
 * parsed with a small dependency-free regex ({@code "occurred_at"\s*:\s*"..."}) to stay pure and
 * avoid import surprises; only a date prefix ({@code yyyy-MM-dd}) of the value is used.
 */
public final class TemporalExtractor {

  private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");

  private static final Pattern N_AGO = Pattern.compile(
    "\\b(\\d+)\\s+(day|days|week|weeks|month|months|year|years)\\s+ago\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern IN_N = Pattern.compile(
    "\\bin\\s+(\\d+)\\s+(day|days|week|weeks|month|months|year|years)\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern SPAN_PHRASE = Pattern.compile(
    "\\b(between|from|how long)\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern SINCE_PHRASE = Pattern.compile(
    "\\b(how long ago|days since|how long since|when did|when was)\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern RESIDUAL_DAY = Pattern.compile(
    "\\b(yesterday|today|tomorrow)\\b", Pattern.CASE_INSENSITIVE);

  /**
   * How many residual references to report before stopping. A handful is enough to keep synthesis
   * honest; beyond that the facts would crowd out the memories themselves in the prompt.
   */
  private static final int MAX_RESIDUAL_FACTS = 6;

  /**
   * Resolves — or, failing an anchor, flags — every relative reference still present in the
   * candidate memories, so synthesis never has to decide for itself what "next month" meant.
   */
  private static void addResidualFacts(List<TemporalFact> facts, Set<String> seen, List<Memory> candidates) {
    if (candidates == null) {
      return;
    }
    int reported = 0;
    for (Memory memory : candidates) {
      if (memory == null || memory.content() == null || memory.content().isBlank()) {
        continue;
      }
      LocalDate anchor = MemoryTimes.anchor(memory);
      for (String reference : residualReferences(memory.content())) {
        if (reported >= MAX_RESIDUAL_FACTS) {
          return;
        }
        int before = facts.size();
        add(facts, seen, residualFact(reference, anchor));
        if (facts.size() > before) {
          reported++;
        }
      }
    }
  }

  // ---- residual references in memory content ----

  /**
   * Every relative reference in one memory's text, in the order they appear.
   */
  private static List<String> residualReferences(String content) {
    List<String> references = new ArrayList<>();
    collect(references, RelativeDates.PERIOD, content);
    collect(references, RelativeDates.FUZZY, content);
    collect(references, RESIDUAL_DAY, content);
    collect(references, N_AGO, content);
    collect(references, IN_N, content);
    return references;
  }

  private static void collect(List<String> references, Pattern pattern, String content) {
    Matcher matcher = pattern.matcher(content);
    while (matcher.find()) {
      references.add(matcher.group().trim());
    }
  }

  /**
   * A resolved fact when the memory records when its content was true, and an explicit
   * leave-it-alone instruction when it does not.
   */
  private static TemporalFact residualFact(String reference, LocalDate anchor) {
    String resolved = anchor == null ? null : resolveResidual(reference, anchor);
    if (resolved == null) {
      return new TemporalFact(
        "\"" + reference + "\" in a memory has no recorded date to anchor it",
        "leave it unresolved — do not infer a date");
    }
    return new TemporalFact(
      "\"" + reference + "\" in the memory dated " + anchor + " resolves to", resolved);
  }

  /**
   * The absolute date/period a reference names, or {@code null} when it has no calendar meaning.
   */
  private static String resolveResidual(String reference, LocalDate anchor) {
    Matcher period = RelativeDates.PERIOD.matcher(reference);
    if (period.matches()) {
      return RelativeDates.period(period.group(1), period.group(2), anchor);
    }
    Matcher day = RESIDUAL_DAY.matcher(reference);
    if (day.matches()) {
      return switch (day.group(1).toLowerCase(Locale.ROOT)) {
        case "yesterday" -> anchor.minusDays(1).toString();
        case "tomorrow" -> anchor.plusDays(1).toString();
        default -> anchor.toString();
      };
    }
    Matcher ago = N_AGO.matcher(reference);
    if (ago.matches()) {
      long n = parseLong(ago.group(1));
      return n < 0 ? null : minus(anchor, n, ago.group(2)).toString();
    }
    Matcher in = IN_N.matcher(reference);
    if (in.matches()) {
      long n = parseLong(in.group(1));
      return n < 0 ? null : plus(anchor, n, in.group(2)).toString();
    }
    return null; // fuzzy: seasons, "a while back" — no calendar definition to resolve to
  }

  private static void add(List<TemporalFact> facts, Set<String> seen, TemporalFact fact) {
    if (seen.add(fact.render())) {
      facts.add(fact);
    }
  }

  // ---- helpers ----

  private static List<LocalDate> parseIsoDates(String q) {
    List<LocalDate> dates = new ArrayList<>();
    Matcher m = ISO_DATE.matcher(q);
    while (m.find()) {
      LocalDate d = safeDate(m.group(0));
      if (d != null) {
        dates.add(d);
      }
    }
    return dates;
  }

  private static LocalDate parseOccurredAt(String payload) {
    return MemoryTimes.dateField(payload, MemoryTimes.OCCURRED_AT);
  }

  private static LocalDate safeDate(String value) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static long parseLong(String s) {
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static LocalDate minus(LocalDate base, long n, String unit) {
    return switch (unit.toLowerCase(Locale.ROOT)) {
      case "week", "weeks" -> base.minusWeeks(n);
      case "month", "months" -> base.minusMonths(n);
      case "year", "years" -> base.minusYears(n);
      default -> base.minusDays(n);
    };
  }

  private static LocalDate plus(LocalDate base, long n, String unit) {
    return switch (unit.toLowerCase(Locale.ROOT)) {
      case "week", "weeks" -> base.plusWeeks(n);
      case "month", "months" -> base.plusMonths(n);
      case "year", "years" -> base.plusYears(n);
      default -> base.plusDays(n);
    };
  }

  /**
   * Signed span from {@code date} to {@code today}: "N days ago", "in N days", or "today".
   */
  private static String signedSpan(LocalDate date, LocalDate today) {
    long days = ChronoUnit.DAYS.between(date, today);
    if (days == 0) {
      return "today";
    }
    if (days > 0) {
      return plural(days) + " ago";
    }
    return "in " + plural(-days);
  }

  private static String plural(long days) {
    return days == 1 ? "1 day" : days + " days";
  }

  private static boolean containsWord(String haystack, String word) {
    return Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE)
      .matcher(haystack).find();
  }

  /**
   * Extract deterministic temporal facts from a query.
   *
   * @param query       the recall query (may be {@code null} or non-temporal)
   * @param requestTime the daemon's request timestamp; anchors "today"
   * @param candidates  retrieval candidates whose {@code event} payloads may carry {@code occurred_at}
   * @return ordered, de-duplicated temporal facts; empty if nothing temporal is detected
   */
  public List<TemporalFact> extract(String query, Instant requestTime, List<Memory> candidates) {
    if (requestTime == null) {
      return List.of();
    }
    LocalDate today = requestTime.atZone(ZoneOffset.UTC).toLocalDate();

    List<TemporalFact> facts = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>(); // dedupe on rendered line

    String q = query == null ? "" : query;

    // 1 + 2: ISO dates in the query.
    List<LocalDate> isoDates = parseIsoDates(q);
    boolean span = SPAN_PHRASE.matcher(q).find();

    if (isoDates.size() >= 2 && span) {
      LocalDate a = isoDates.get(0);
      LocalDate b = isoDates.get(1);
      long days = Math.abs(ChronoUnit.DAYS.between(a, b));
      add(facts, seen, new TemporalFact(
        "days between " + a + " and " + b, plural(days)));
    } else {
      for (LocalDate date : isoDates) {
        add(facts, seen, new TemporalFact(
          "days from " + date + " to today (" + today + ")", signedSpan(date, today)));
      }
    }

    // 3: today / yesterday / tomorrow.
    if (containsWord(q, "today")) {
      add(facts, seen, new TemporalFact("today resolves to", today.toString()));
    }
    if (containsWord(q, "yesterday")) {
      add(facts, seen, new TemporalFact("yesterday resolves to", today.minusDays(1).toString()));
    }
    if (containsWord(q, "tomorrow")) {
      add(facts, seen, new TemporalFact("tomorrow resolves to", today.plusDays(1).toString()));
    }

    // 4: N day/week/month ago.
    Matcher ago = N_AGO.matcher(q);
    while (ago.find()) {
      long n = parseLong(ago.group(1));
      if (n < 0) {
        continue;
      }
      LocalDate resolved = minus(today, n, ago.group(2));
      add(facts, seen, new TemporalFact(
        n + " " + ago.group(2).toLowerCase(Locale.ROOT) + " ago resolves to", resolved.toString()));
    }

    // 5: in N day/week/month.
    Matcher in = IN_N.matcher(q);
    while (in.find()) {
      long n = parseLong(in.group(1));
      if (n < 0) {
        continue;
      }
      LocalDate resolved = plus(today, n, in.group(2));
      add(facts, seen, new TemporalFact(
        "in " + n + " " + in.group(2).toLowerCase(Locale.ROOT) + " resolves to", resolved.toString()));
    }

    // 6: last week / next week.
    if (q.toLowerCase(Locale.ROOT).contains("last week")) {
      add(facts, seen, new TemporalFact("last week resolves to", today.minusDays(7).toString()));
    }
    if (q.toLowerCase(Locale.ROOT).contains("next week")) {
      add(facts, seen, new TemporalFact("next week resolves to", today.plusDays(7).toString()));
    }

    // 7: event memory occurred_at, only when the query asks "how long ago"/"since"/"when".
    if (candidates != null && SINCE_PHRASE.matcher(q).find()) {
      for (Memory memory : candidates) {
        if (memory == null || memory.type() != MemoryType.EVENT) {
          continue;
        }
        LocalDate occurred = parseOccurredAt(memory.payload());
        if (occurred == null) {
          continue;
        }
        String label = memory.content() == null ? "" : memory.content();
        add(facts, seen, new TemporalFact(
          "days since event (" + occurred + "): " + label, signedSpan(occurred, today)));
      }
    }

    // 8: relative references that survived into the memories themselves.
    addResidualFacts(facts, seen, candidates);

    return facts;
  }
}
