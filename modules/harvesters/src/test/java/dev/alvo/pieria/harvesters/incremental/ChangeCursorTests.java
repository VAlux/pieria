package dev.alvo.pieria.harvesters.incremental;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeCursorTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

  @Test
  void roundTripsEveryField() {
    ChangeCursor cursor = ChangeCursor.read(null);
    cursor.since(T);
    cursor.windowStart(T.plusSeconds(60));
    cursor.lastSweep(T.plusSeconds(120));
    cursor.remember("10001", "42", T);

    ChangeCursor read = ChangeCursor.read(MAPPER.readTree(cursor.toJson().toString()));

    assertThat(read.since()).isEqualTo(T);
    assertThat(read.windowStart()).isEqualTo(T.plusSeconds(60));
    assertThat(read.lastSweep()).isEqualTo(T.plusSeconds(120));
    assertThat(read.reported("10001", "42")).isTrue();
    assertThat(read.toJson()).isEqualTo(cursor.toJson());
  }

  @Test
  void freshCursorHasNothing() {
    ChangeCursor cursor = ChangeCursor.read(null);
    assertThat(cursor.since()).isNull();
    assertThat(cursor.windowStart()).isNull();
    assertThat(cursor.lastSweep()).isNull();
    assertThat(cursor.toJson().get("v").intValue()).isEqualTo(1);
  }

  @Test
  void unreadableCursorStartsFresh() {
    for (String json : new String[]{"{\"v\":2,\"since\":\"2026-10-01T10:00:00Z\"}", "{\"v\":1,\"recent\":5}",
      "{\"v\":1,\"since\":\"yesterday\"}", "{\"v\":1,\"recent\":{\"a\":[\"1\"]}}", "\"text\""}) {
      JsonNode node = MAPPER.readTree(json);
      ChangeCursor cursor = ChangeCursor.read(node);
      assertThat(cursor.since()).as(json).isNull();
      assertThat(cursor.reported("a", "1")).as(json).isFalse();
    }
  }

  @Test
  void pruneDropsOnlyOlderEntries() {
    ChangeCursor cursor = ChangeCursor.read(null);
    cursor.remember("old", "1", T);
    cursor.remember("new", "1", T.plusSeconds(600));

    cursor.prune(T.plusSeconds(1));

    assertThat(cursor.reported("old", "1")).isFalse();
    assertThat(cursor.reported("new", "1")).isTrue();
  }

  @Test
  void oldestEntriesGoBeyondTheCap() {
    ChangeCursor cursor = ChangeCursor.read(null);
    for (int i = 0; i <= ChangeCursor.MAX_RECENT; i++) {
      cursor.remember("id" + i, "1", T.plusSeconds(i));
    }

    assertThat(cursor.reported("id0", "1")).isFalse();
    assertThat(cursor.reported("id1", "1")).isTrue();
    assertThat(cursor.reported("id" + ChangeCursor.MAX_RECENT, "1")).isTrue();
  }

  @Test
  void reportedMatchesOnlyTheSameVersion() {
    ChangeCursor cursor = ChangeCursor.read(null);
    cursor.remember("a", "1", T);
    assertThat(cursor.reported("a", "1")).isTrue();
    assertThat(cursor.reported("a", "2")).isFalse();
    assertThat(cursor.reported("b", "1")).isFalse();

    cursor.remember("a", "2", T.plusSeconds(1));
    assertThat(cursor.reported("a", "2")).isTrue();
    assertThat(cursor.reported("a", "1")).isFalse();
  }
}
