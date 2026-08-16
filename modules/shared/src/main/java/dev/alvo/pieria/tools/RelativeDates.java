package dev.alvo.pieria.tools;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic resolution of relative date expressions against the date they were spoken on.
 *
 * <p>Shared because both ends of the pipeline need the <em>same</em> arithmetic: ingestion rewrites
 * these expressions out of a transcript while it still knows when each turn was spoken, and
 * retrieval resolves whatever survived into a temporal fact for synthesis. If the two computed dates
 * differently, a memory and the answer quoting it would disagree.
 *
 * <p>Resolution is always at the granularity the speaker used — a month-precision phrase yields a
 * month, a week-precision phrase a week — so nothing is invented. {@link #FUZZY} matches the
 * references that have no calendar definition at all; those are never resolved, only reported.
 */
public final class RelativeDates {

  private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
  private static final DateTimeFormatter MONTH_YEAR =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

  /**
   * Calendar periods relative to the speaking date: group 1 is the modifier, group 2 the unit. A
   * leading article is consumed so "the past month" resolves to "April 2026" rather than
   * "the April 2026"; the week and weekend forms reinstate their own "the".
   */
  public static final Pattern PERIOD = Pattern.compile(
    "\\b(?:the\\s+)?(last|past|this|next|coming)\\s+(week|weekend|month|year)\\b",
    Pattern.CASE_INSENSITIVE);

  /**
   * References with no calendar definition, which must never be resolved to a date. Seasons are
   * hemisphere-dependent and span months; "recently" and "a while back" are not measurements. When
   * one of these reaches synthesis it should be reported as unresolvable rather than guessed at.
   */
  public static final Pattern FUZZY = Pattern.compile(
    "\\b(?:(?:last|past|this|next|coming)\\s+(?:spring|summer|autumn|fall|winter)"
      + "|a\\s+while\\s+(?:ago|back)|recently|some\\s+time\\s+ago|soon)\\b",
    Pattern.CASE_INSENSITIVE);

  private RelativeDates() {
  }

  /**
   * The absolute period named by a {@link #PERIOD} match, at the same granularity.
   *
   * @param modifier group 1 of the match ("last", "past", "this", "next", "coming")
   * @param unit     group 2 of the match ("week", "weekend", "month", "year")
   * @param anchor   the date the expression was spoken on
   */
  public static String period(String modifier, String unit, LocalDate anchor) {
    int offset = switch (modifier.toLowerCase(Locale.ROOT)) {
      case "last", "past" -> -1;
      case "next", "coming" -> 1;
      default -> 0; // "this"
    };
    return switch (unit.toLowerCase(Locale.ROOT)) {
      // ISO weeks run Monday–Sunday, so naming the Monday (or the Saturday, for a weekend) pins the
      // span to one unambiguous date while still reading as a span rather than a single day.
      case "week" -> "the week of " + anchor.with(DayOfWeek.MONDAY).plusWeeks(offset).format(ISO_DATE);
      case "weekend" -> "the weekend of " + anchor.with(DayOfWeek.SATURDAY).plusWeeks(offset).format(ISO_DATE);
      case "month" -> anchor.plusMonths(offset).format(MONTH_YEAR);
      default -> String.valueOf(anchor.plusYears(offset).getYear()); // "year"
    };
  }
}
