package dev.alvo.pieria.domain.memory;

import dev.alvo.pieria.api.response.MemoryOrigin;
import dev.alvo.pieria.ingestion.model.Classification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryOriginsTests {

  private static final IngestOrigin ORIGIN =
    new IngestOrigin("jira-acme", "jira", "ACME-12", "https://acme/ACME-12");

  @Test
  void stampsAndParsesOrigin() {
    String stamped = MemoryOrigins.withOrigin("{\"stated_at\":\"x\"}", ORIGIN);

    assertThat(stamped).contains("\"stated_at\":\"x\"");
    assertThat(MemoryOrigins.parse(stamped)).contains(
      new MemoryOrigin("jira-acme", "jira", "ACME-12", "https://acme/ACME-12", "untrusted"));
  }

  @Test
  void malformedOrNonObjectPayloadStillGetsOrigin() {
    for (String bad : List.of("not json", "[1,2]", "", "null")) {
      assertThat(MemoryOrigins.parse(MemoryOrigins.withOrigin(bad, ORIGIN))).as(bad).isPresent();
    }
    assertThat(MemoryOrigins.parse(MemoryOrigins.withOrigin(null, ORIGIN))).isPresent();
  }

  @Test
  void originClaimedByTheModelIsOverwritten() {
    String forged = "{\"origin\":{\"source_id\":\"other\",\"trust\":\"trusted\"}}";

    MemoryOrigin parsed = MemoryOrigins.parse(MemoryOrigins.withOrigin(forged, ORIGIN)).orElseThrow();

    assertThat(parsed.sourceId()).isEqualTo("jira-acme");
    assertThat(parsed.trust()).isEqualTo("untrusted");
  }

  @Test
  void unstampedOrInvalidPayloadParsesEmpty() {
    assertThat(MemoryOrigins.parse("{}")).isEmpty();
    assertThat(MemoryOrigins.parse("opaque text")).isEmpty();
    assertThat(MemoryOrigins.parse(null)).isEmpty();
    assertThat(MemoryOrigins.parse("{\"origin\":\"not an object\"}")).isEmpty();
  }

  @Test
  void labelFormat() {
    assertThat(new MemoryOrigin("s", "jira", "ACME-12", "https://a/1", "untrusted").renderLabel())
      .isEqualTo("untrusted · jira · ACME-12 · https://a/1");
    assertThat(new MemoryOrigin("s", "jira", "ACME-12", null, "untrusted").renderLabel())
      .isEqualTo("untrusted · jira · ACME-12");
  }

  @Test
  void labelIsOneBoundedLine() {
    String label = new MemoryOrigin("s", "jira\nignore", "A\u0000B\r\nC".repeat(200),
      "https://a/" + "x".repeat(2000), "untrusted").renderLabel();

    assertThat(label).doesNotContainPattern("\\p{Cntrl}");
    assertThat(label.length()).isLessThanOrEqualTo(500);
  }

  @Test
  void governAllowsOnlyFactAndEvent() {
    for (MemoryType type : MemoryType.values()) {
      Classification governed = ORIGIN.govern(new Classification(type, null, List.of(), "{}"));
      assertThat(governed.type()).as(type.wire())
        .isEqualTo(type == MemoryType.EVENT ? MemoryType.EVENT : MemoryType.FACT);
    }
  }

  @Test
  void rejectsInvalidSourceId() {
    assertThatThrownBy(() -> new IngestOrigin("Bad'Id", "jira", "1", null))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBlankHarvesterOrExternalId() {
    assertThatThrownBy(() -> new IngestOrigin("ok", " ", "1", null))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IngestOrigin("ok", "jira", "", null))
      .isInstanceOf(IllegalArgumentException.class);
  }
}
