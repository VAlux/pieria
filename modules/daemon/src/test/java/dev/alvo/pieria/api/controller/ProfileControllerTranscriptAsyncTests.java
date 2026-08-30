package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.ExportLineResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.TaskSubmitResponse;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.ingestion.ChunkLedgerMode;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.ingestion.trace.TraceIngestionService;
import dev.alvo.pieria.ingestion.transcript.TranscriptParser;
import dev.alvo.pieria.ingestion.transcript.TranscriptParserRegistry;
import dev.alvo.pieria.profile.ProfileService;
import dev.alvo.pieria.profile.ProfileStatsService;
import dev.alvo.pieria.retrieval.RetrievalService;
import dev.alvo.pieria.task.TaskRegistry;
import dev.alvo.pieria.task.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfileControllerTranscriptAsyncTests {

  private final TaskRegistry tasks = new TaskRegistry();

  @AfterEach
  void tearDown() {
    tasks.shutdown();
  }

  @Test
  void asyncTranscriptReturnsBeforeSlowExtractionFinishes() throws Exception {
    IngestionService ingestion = mock(IngestionService.class);
    CountDownLatch extractionStarted = new CountDownLatch(1);
    CountDownLatch releaseExtraction = new CountDownLatch(1);
    when(ingestion.ingest(eq("proj"), eq("s1"), anyList(), isNull(),
      eq(ChunkLedgerMode.DEFER_TRAILING), any())).thenAnswer(invocation -> {
        extractionStarted.countDown();
        assertThat(releaseExtraction.await(5, TimeUnit.SECONDS)).isTrue();
        return List.of();
      });

    TranscriptParser parser = new TranscriptParser() {
      @Override
      public String harness() {
        return "codex";
      }

      @Override
      public List<Message> parse(String transcript, String sessionId) {
        return List.of(Message.of(sessionId, "user", "remember this"));
      }
    };
    ProfileController controller = controller(ingestion, new TranscriptParserRegistry(List.of(parser)));

    try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var response = caller.submit(() ->
        controller.ingestTranscriptAsync("proj", "s1", "codex", true, "transcript"));

      TaskSubmitResponse submitted = response.get(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
      assertThat(extractionStarted.await(1, TimeUnit.SECONDS)).isTrue();
      UUID taskId = UUID.fromString(submitted.taskId());
      assertThat(tasks.find(taskId).orElseThrow().status())
        .isEqualTo(TaskStatus.RUNNING);

      releaseExtraction.countDown();
      assertThat(awaitTerminal(taskId)).isEqualTo(TaskStatus.SUCCEEDED);
    } finally {
      releaseExtraction.countDown();
    }
  }

  private TaskStatus awaitTerminal(UUID taskId) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      TaskStatus status = tasks.find(taskId).orElseThrow().status();
      if (status != TaskStatus.RUNNING) {
        return status;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("transcript ingest task did not finish");
  }

  @SuppressWarnings("unchecked")
  private ProfileController controller(IngestionService ingestion, TranscriptParserRegistry parsers) {
    Converter<Memory, MemoryResponse> memoryConverter = mock(Converter.class);
    Converter<ExportRow, ExportLineResponse> exportConverter = mock(Converter.class);
    return new ProfileController(ingestion, mock(TraceIngestionService.class),
      mock(RetrievalService.class), mock(ProfileService.class),
      mock(ProfileStatsService.class), JsonMapper.builder().build(), tasks, memoryConverter,
      exportConverter, parsers);
  }
}
