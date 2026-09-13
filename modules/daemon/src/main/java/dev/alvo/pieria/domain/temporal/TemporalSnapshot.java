package dev.alvo.pieria.domain.temporal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/** One immutable request clock and committed transaction watermark shared by every channel. */
public record TemporalSnapshot(Instant asOf, Instant knownAsOf, long sequence, boolean explicit) {
  private static final DateTimeFormatter STORAGE = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

  public static TemporalSnapshot resolve(String asOf, String knownAsOf, Instant now, long sequence) {
    Instant world = asOf == null ? now : parse(asOf);
    Instant knowledge = knownAsOf == null ? now : parse(knownAsOf);
    if (knowledge.isAfter(now)) throw new IllegalArgumentException("knownAsOf cannot be in the future");
    return new TemporalSnapshot(world, knowledge, sequence, asOf != null || knownAsOf != null);
  }

  public static Instant parse(String value) {
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Expected an ISO timestamp with an offset: " + value, e);
    }
  }

  public static String timestamp(Instant value) {
    return value == null ? null : STORAGE.format(value);
  }
}
