package dev.alvo.pieria.task;

import dev.alvo.pieria.model.ModelUnavailableException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TaskRegistry}: submitted work runs on a background thread, its terminal state and
 * result become observable through {@link TaskRegistry#find}, and failures are classified so a
 * polling client can map them to the right outcome.
 */
class TaskRegistryTests {

  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final TaskRegistry registry = new TaskRegistry();

  private TaskSnapshot awaitTerminal(UUID id) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      TaskSnapshot s = registry.find(id).orElseThrow(() -> new AssertionError("task vanished"));
      if (s.isTerminal()) {
        return s;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("task did not reach a terminal state in time");
  }

  @Test
  void successfulTaskExposesResult() throws InterruptedException {
    UUID id = registry.submit(progress -> {
      progress.onPhase("extract", 1, 1);
      return mapper.valueToTree(Map.of("count", 3));
    });

    TaskSnapshot s = awaitTerminal(id);
    assertThat(s.status()).isEqualTo(TaskStatus.SUCCEEDED);
    assertThat(s.result().get("count").asInt()).isEqualTo(3);
    assertThat(s.errorKind()).isNull();
  }

  @Test
  void runtimeFailureIsRecordedAsFailure() throws InterruptedException {
    UUID id = registry.submit(progress -> {
      throw new IllegalStateException("boom");
    });

    TaskSnapshot s = awaitTerminal(id);
    assertThat(s.status()).isEqualTo(TaskStatus.FAILED);
    assertThat(s.errorKind()).isEqualTo("failure");
    assertThat(s.errorMessage()).contains("boom");
  }

  @Test
  void modelUnavailableIsClassified() throws InterruptedException {
    UUID id = registry.submit(progress -> {
      throw new ModelUnavailableException("provider down");
    });

    TaskSnapshot s = awaitTerminal(id);
    assertThat(s.status()).isEqualTo(TaskStatus.FAILED);
    assertThat(s.errorKind()).isEqualTo("model-unavailable");
  }

  @Test
  void unknownTaskIsEmpty() {
    assertThat(registry.find(UUID.randomUUID())).isEmpty();
  }
}
