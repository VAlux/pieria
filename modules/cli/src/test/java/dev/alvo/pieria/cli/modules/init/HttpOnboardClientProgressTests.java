package dev.alvo.pieria.cli.modules.init;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.cli.log.ProgressListener;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the real {@link HttpOnboardClient} polling path against a {@link StubDaemon}: it submits to
 * {@code /onboard/async}, polls {@code /v1/tasks/{id}} forwarding each RUNNING update to the progress
 * listener, and maps the terminal task to the right {@link OnboardClient.OnboardResult}.
 */
class HttpOnboardClientProgressTests {

  private static SourceSpec spec() {
    return new SourceSpec.Markdown("/tmp/proj", false, 1);
  }

  @Test
  void forwardsPerPhaseProgressThenSucceeds() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stubSequence("/tasks/t1",
        "{\"status\":\"RUNNING\",\"phase\":\"extract\",\"done\":1,\"total\":2}",
        "{\"status\":\"RUNNING\",\"phase\":\"verify\",\"done\":1,\"total\":1}",
        "{\"status\":\"SUCCEEDED\",\"result\":{\"sourceType\":\"markdown\",\"documents\":1,\"memoriesStored\":4}}");

      List<String> phases = new ArrayList<>();
      OnboardClient.OnboardResult result = new HttpOnboardClient(daemon.baseUrl())
        .onboard("proj", spec(), (phase, done, total) -> phases.add(phase + ":" + done + "/" + total));

      assertThat(result).isInstanceOf(OnboardClient.Success.class);
      OnboardClient.Success success = (OnboardClient.Success) result;
      assertThat(success.memoriesStored()).isEqualTo(4);
      assertThat(success.documents()).isEqualTo(1);
      assertThat(success.symbols()).isNull();
      assertThat(phases).containsExactly("extract:1/2", "verify:1/1");
    }
  }

  @Test
  void failedTaskWithModelUnavailableMapsToModelUnavailableWithReason() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/onboard/async", 202, "{\"taskId\":\"t1\"}");
      daemon.stub("/tasks/t1", 200,
        "{\"status\":\"FAILED\",\"errorKind\":\"model-unavailable\","
          + "\"errorMessage\":\"HTTP 404: model or deployment not found\"}");

      OnboardClient.OnboardResult result = new HttpOnboardClient(daemon.baseUrl())
        .onboard("proj", spec(), ProgressListener.noop());

      assertThat(result).isInstanceOf(OnboardClient.ModelUnavailable.class);
      assertThat(((OnboardClient.ModelUnavailable) result).reason())
        .isEqualTo("HTTP 404: model or deployment not found");
    }
  }
}
