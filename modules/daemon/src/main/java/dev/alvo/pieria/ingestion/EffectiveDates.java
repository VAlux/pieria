package dev.alvo.pieria.ingestion;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.ingestion.model.Chunk;
import dev.alvo.pieria.tools.Hash;
import dev.alvo.pieria.tools.RelativeDates;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Ground effective phrases in a referenced source turn before resolving dates in Java. */
final class EffectiveDates {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private EffectiveDates() {}

  static Memory apply(Memory memory, Chunk chunk) {
    if (chunk == null) return memory;
    try {
      var parsed = MAPPER.readTree(memory.payload());
      if (!(parsed instanceof ObjectNode payload)) return memory;
      int absolute = payload.path("source_message_index").asInt(-1);
      int local = absolute - chunk.firstMessageIndex();
      Message turn = local >= 0 && local < chunk.messages().size() ? chunk.messages().get(local) : null;
      Instant stated = turn == null ? null : turn.createdAt();
      String source = turn == null ? chunk.transcript() : turn.content();
      String occurrence = turn == null ? "chunk:" + Hash.hash128(memory.sessionId(), chunk.transcript())
        : "turn:" + Hash.hash128(memory.sessionId(), Integer.toString(absolute), turn.role(), turn.content());
      if (stated != null) payload.put("stated_at", stated.toString());
      payload.put("temporal_source", turn == null ? "chunk-fallback" : "source-turn");
      String phrase = payload.path("effective_phrase").asText("").trim();
      Instant from = null, to = null;
      if (!phrase.isEmpty()) {
        boolean period = payload.path("effective_kind").asText("").equals("period");
        Bounds bounds = source.toLowerCase(Locale.ROOT).contains(phrase.toLowerCase(Locale.ROOT))
          ? resolve(phrase, stated, period) : null;
        if (bounds == null) payload.put("temporal_unresolved", true);
        else { from = bounds.from(); to = bounds.to(); }
      } else payload.put("temporal_default", stated == null ? "store-time" : "stated-at");
      return new Memory(memory.id(), memory.sessionId(), memory.type(), memory.content(), memory.topicKey(),
        memory.supersedes(), memory.superseded(), payload.toString(), memory.embedText(), memory.createdAt())
        .withValidity(from, to, occurrence);
    } catch (RuntimeException e) { return memory; }
  }

  record Bounds(Instant from, Instant to) {}
  static Bounds resolve(String phrase, Instant anchor, boolean period) {
    String text = phrase.trim();
    if (text.toLowerCase(Locale.ROOT).matches(".*(sometime|some time|around|approximately|maybe).*")) return null;
    try { return new Bounds(OffsetDateTime.parse(text).toInstant(), null); } catch (RuntimeException ignored) {}
    try { return new Bounds(LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant(), null); } catch (RuntimeException ignored) {}
    if (anchor != null) {
      LocalDate date = anchor.atOffset(ZoneOffset.UTC).toLocalDate();
      int days = switch (text.toLowerCase(Locale.ROOT)) { case "yesterday" -> -1; case "today" -> 0; case "tomorrow" -> 1; default -> Integer.MIN_VALUE; };
      if (days != Integer.MIN_VALUE) return new Bounds(date.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant(), null);
      var relative = RelativeDates.PERIOD.matcher(text);
      if (relative.matches()) text = RelativeDates.period(relative.group(1), relative.group(2), date);
    }
    // A month-only change date is uncertain. Only explicit applicability for a whole period yields bounds.
    if (!period) return null;
    try {
      YearMonth month = YearMonth.parse(text, DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));
      return new Bounds(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(), month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    } catch (RuntimeException ignored) {}
    if (text.startsWith("the week of ") || text.startsWith("the weekend of ")) {
      try {
        LocalDate start = LocalDate.parse(text.substring(text.lastIndexOf(' ') + 1));
        return new Bounds(start.atStartOfDay(ZoneOffset.UTC).toInstant(), start.plusDays(text.startsWith("the week of ") ? 7 : 2).atStartOfDay(ZoneOffset.UTC).toInstant());
      } catch (RuntimeException ignored) {}
    }
    return null;
  }
}
