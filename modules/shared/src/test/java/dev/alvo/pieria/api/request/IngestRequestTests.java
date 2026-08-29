package dev.alvo.pieria.api.request;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestRequestTests {

  private static final IngestRequest.MessageDto MESSAGE =
    new IngestRequest.MessageDto("user", "hello");

  private static final TraceEventDto TRACE = new TraceEventDto(
    "Bash", "./gradlew test", "BUILD FAILED", TraceStatus.FAILURE, 1, null, null, null);

  @Test
  void messagesOnlyRequestIsIngestible() {
    assertThat(new IngestRequest("s1", List.of(MESSAGE)).hasIngestibleContent()).isTrue();
  }

  @Test
  void tracesOnlyRequestIsIngestible() {
    IngestRequest request = new IngestRequest("s1", null, null, null, List.of(TRACE));

    assertThat(request.hasIngestibleContent()).isTrue();
    assertThat(request.messages()).isEmpty();
  }

  @Test
  void mixedRequestIsIngestible() {
    assertThat(new IngestRequest("s1", List.of(MESSAGE), null, null, List.of(TRACE))
      .hasIngestibleContent()).isTrue();
  }

  @Test
  void emptyRequestIsNotIngestible() {
    assertThat(new IngestRequest("s1", List.of()).hasIngestibleContent()).isFalse();
    assertThat(new IngestRequest("s1", null, null, null, null).hasIngestibleContent()).isFalse();
  }

  // Null lists are normalized so callers never branch on null; the legacy two- and three-arg
  // constructors must keep working unchanged for every existing caller.
  @Test
  void listsAreNeverNull() {
    IngestRequest request = new IngestRequest("s1", null, null, null, null);

    assertThat(request.messages()).isEmpty();
    assertThat(request.traces()).isEmpty();
  }

  @Test
  void traceStatusParsesLeniently() {
    assertThat(TraceStatus.fromWire("  Failure ")).isEqualTo(TraceStatus.FAILURE);
    assertThat(TraceStatus.fromWire(null)).isEqualTo(TraceStatus.UNKNOWN);
    assertThat(TraceStatus.fromWire("nonsense")).isEqualTo(TraceStatus.UNKNOWN);
    assertThat(TraceStatus.SUCCESS.wire()).isEqualTo("success");
  }
}
