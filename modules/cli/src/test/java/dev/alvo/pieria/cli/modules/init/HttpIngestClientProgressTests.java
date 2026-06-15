package dev.alvo.pieria.cli.modules.init;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.request.IngestRequest.MessageDto;
import dev.alvo.pieria.cli.log.ProgressListener;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the real {@link HttpIngestClient} polling path against a {@link StubDaemon}: it submits to
 * {@code /ingest/async}, polls {@code /v1/tasks/{id}} forwarding each RUNNING update to the progress
 * listener, and maps the terminal task to the right {@link IngestClient.IngestResult}.
 */
class HttpIngestClientProgressTests {

  private static IngestRequest request() {
    return new IngestRequest("s1", List.of(new MessageDto("user", "hi")));
  }

  @Test
  void forwardsPerPhaseProgressThenSucceeds() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/ingest/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stubSequence("/tasks/t1",
        "{\"status\":\"RUNNING\",\"phase\":\"extract\",\"done\":1,\"total\":2}",
        "{\"status\":\"RUNNING\",\"phase\":\"verify\",\"done\":1,\"total\":1}",
        "{\"status\":\"SUCCEEDED\",\"result\":{\"count\":4}}");

      List<String> phases = new ArrayList<>();
      IngestClient.IngestResult result = new HttpIngestClient(daemon.baseUrl())
        .ingest("proj", request(), (phase, done, total) -> phases.add(phase + ":" + done + "/" + total));

      assertThat(result).isInstanceOf(IngestClient.Success.class);
      assertThat(((IngestClient.Success) result).count()).isEqualTo(4);
      assertThat(phases).containsExactly("extract:1/2", "verify:1/1");
    }
  }

  @Test
  void failedTaskWithModelUnavailableMapsToModelUnavailable() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/ingest/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stub("/tasks/t1", 200, "{\"status\":\"FAILED\",\"errorKind\":\"model-unavailable\"}");

      IngestClient.IngestResult result = new HttpIngestClient(daemon.baseUrl())
        .ingest("proj", request(), ProgressListener.noop());

      assertThat(result).isInstanceOf(IngestClient.ModelUnavailable.class);
    }
  }
}
