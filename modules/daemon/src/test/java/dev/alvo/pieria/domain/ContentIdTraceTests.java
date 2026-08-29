package dev.alvo.pieria.domain;

import dev.alvo.pieria.api.request.TraceStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ContentIdTraceTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  @Test
  void idIsStableAcrossCalls() {
    String first = ContentId.forTrace("p1", "s1", "Bash", "./gradlew test", TraceStatus.FAILURE, AT);
    String second = ContentId.forTrace("p1", "s1", "Bash", "./gradlew test", TraceStatus.FAILURE, AT);

    assertThat(first).isEqualTo(second);
  }

  @Test
  void idIs32HexCharacters() {
    String id = ContentId.forTrace("p1", "s1", "Bash", "./gradlew test", TraceStatus.FAILURE, AT);

    assertThat(id).hasSize(32).matches("[0-9a-f]{32}");
  }

  // Profile scoping mirrors messages and memories: identical traces coexist across profiles,
  // re-ingest within one profile is a no-op.
  @Test
  void differentProfilesGetDifferentIds() {
    assertThat(ContentId.forTrace("p1", "s1", "Bash", "x", TraceStatus.SUCCESS, AT))
      .isNotEqualTo(ContentId.forTrace("p2", "s1", "Bash", "x", TraceStatus.SUCCESS, AT));
  }

  // The regression this pins: hashing the CommandSignature instead of the full args would
  // collapse these two and silently drop the second trace.
  @Test
  void argsThatDifferOnlyByFlagsGetDifferentIds() {
    assertThat(ContentId.forTrace("p1", "s1", "Bash", "./gradlew test", TraceStatus.SUCCESS, AT))
      .isNotEqualTo(
        ContentId.forTrace("p1", "s1", "Bash", "./gradlew test --rerun-tasks", TraceStatus.SUCCESS, AT));
  }

  @Test
  void statusAndTimeParticipateInTheId() {
    String base = ContentId.forTrace("p1", "s1", "Bash", "x", TraceStatus.SUCCESS, AT);

    assertThat(base).isNotEqualTo(ContentId.forTrace("p1", "s1", "Bash", "x", TraceStatus.FAILURE, AT));
    assertThat(base).isNotEqualTo(
      ContentId.forTrace("p1", "s1", "Bash", "x", TraceStatus.SUCCESS, AT.plusSeconds(1)));
  }

  @Test
  void nullFieldsAreTolerated() {
    assertThat(ContentId.forTrace(null, null, "Bash", null, TraceStatus.UNKNOWN, null))
      .hasSize(32);
  }
}
