package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.request.IngestRequest.MessageDto;
import dev.alvo.pieria.api.response.ExportLineResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.ingestion.trace.TraceIngestionService;
import dev.alvo.pieria.ingestion.transcript.TranscriptParserRegistry;
import dev.alvo.pieria.profile.ProfileService;
import dev.alvo.pieria.profile.ProfileStatsService;
import dev.alvo.pieria.retrieval.RetrievalService;
import dev.alvo.pieria.task.TaskRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.core.convert.converter.Converter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The occurrence-time contract of {@code POST /ingest}: a replayed or back-filled transcript says
 * when it happened, and the daemon must carry that onto the domain messages rather than assuming the
 * ingest is happening now. Without it, {@code TranscriptNormalizer} resolves "yesterday" against the
 * ingest wall clock and a historical corpus lands years off.
 */
class ProfileControllerIngestTimestampTests {

  private static final Instant SESSION_1 = Instant.parse("2023-05-08T13:56:00Z");
  private static final Instant SESSION_2 = Instant.parse("2023-06-09T10:15:00Z");

  private final TaskRegistry tasks = new TaskRegistry();
  private final IngestionService ingestion = mock(IngestionService.class);

  @AfterEach
  void tearDown() {
    tasks.shutdown();
  }

  @Test
  void perMessageTimestampsReachTheDomainMessages() {
    when(ingestion.ingest(anyString(), anyString(), anyList(), ArgumentMatchers.<Integer>isNull())).thenReturn(List.of());

    controller().ingest("proj", new IngestRequest("s1", List.of(
      new MessageDto("user", "met yesterday", SESSION_1),
      new MessageDto("assistant", "met yesterday", SESSION_2))));

    assertThat(captureMessages()).extracting(Message::createdAt).containsExactly(SESSION_1, SESSION_2);
  }

  @Test
  void requestOccurredAtAppliesToMessagesWithoutTheirOwnTimestamp() {
    when(ingestion.ingest(anyString(), anyString(), anyList(), ArgumentMatchers.<Integer>isNull())).thenReturn(List.of());

    controller().ingest("proj", new IngestRequest("s1", List.of(
      new MessageDto("user", "no timestamp of its own"),
      new MessageDto("assistant", "has one", SESSION_2)), null, SESSION_1));

    assertThat(captureMessages()).extracting(Message::createdAt).containsExactly(SESSION_1, SESSION_2);
  }

  @Test
  void withoutAnyTimestampMessagesStayUnstampedSoTheDaemonClockApplies() {
    when(ingestion.ingest(anyString(), anyString(), anyList(), ArgumentMatchers.<Integer>isNull())).thenReturn(List.of());

    controller().ingest("proj", new IngestRequest("s1", List.of(new MessageDto("user", "hi"))));

    assertThat(captureMessages()).extracting(Message::createdAt).containsOnlyNulls();
  }

  @SuppressWarnings("unchecked")
  private List<Message> captureMessages() {
    ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
    verify(ingestion).ingest(anyString(), anyString(), captor.capture(), ArgumentMatchers.<Integer>isNull());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private ProfileController controller() {
    return new ProfileController(ingestion, mock(TraceIngestionService.class),
      mock(RetrievalService.class), mock(ProfileService.class),
      mock(ProfileStatsService.class), JsonMapper.builder().build(), tasks,
      mock(Converter.class), mock(Converter.class), new TranscriptParserRegistry(List.of()));
  }
}
