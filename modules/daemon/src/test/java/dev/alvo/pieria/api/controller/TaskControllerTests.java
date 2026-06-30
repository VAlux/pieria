package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.TaskListResponse;
import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.api.response.TaskSummary;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.task.TaskRegistry;
import dev.alvo.pieria.task.TaskStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TaskController}: it exposes registry tasks for listing and single-status polling,
 * cancels by id, and maps unknown / malformed ids to a 404 {@link NotFoundException}.
 */
class TaskControllerTests {

  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final TaskRegistry registry = new TaskRegistry();
  private final TaskController controller = new TaskController(registry);

  private void awaitTerminal(UUID id) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (registry.find(id).orElseThrow().status() != TaskStatus.RUNNING) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("task did not finish in time");
  }

  @Test
  void listAndStatusExposeTaskMetadata() throws InterruptedException {
    UUID id = registry.submit("onboard", "pieria", progress -> {
      progress.onPhase("extract", 1, 1);
      return mapper.valueToTree(Map.of("count", 2));
    });
    awaitTerminal(id);

    TaskListResponse list = controller.list();
    TaskSummary summary = list.tasks().stream()
      .filter(t -> t.id().equals(id.toString())).findFirst().orElseThrow();
    assertThat(summary.kind()).isEqualTo("onboard");
    assertThat(summary.profile()).isEqualTo("pieria");
    assertThat(summary.status()).isEqualTo("SUCCEEDED");
    assertThat(summary.startedAtEpochMs()).isPositive();

    TaskStatusResponse status = controller.status(id.toString());
    assertThat(status.kind()).isEqualTo("onboard");
    assertThat(status.profile()).isEqualTo("pieria");
    assertThat(status.status()).isEqualTo("SUCCEEDED");
    assertThat(status.result().get("count").asInt()).isEqualTo(2);
  }

  @Test
  void statusForUnknownOrMalformedIdIsNotFound() {
    assertThatThrownBy(() -> controller.status(UUID.randomUUID().toString()))
      .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> controller.status("not-a-uuid"))
      .isInstanceOf(NotFoundException.class);
  }

  @Test
  void cancelUnknownIdIsNotFound() {
    assertThatThrownBy(() -> controller.cancel(UUID.randomUUID().toString()))
      .isInstanceOf(NotFoundException.class);
  }

  @Test
  void cancelRunningTaskDrivesItToCancelled() throws InterruptedException {
    CountDownLatch started = new CountDownLatch(1);
    UUID id = registry.submit("ingest", "p", progress -> {
      started.countDown();
      for (int i = 0; i < 100_000; i++) {
        progress.onPhase("extract", i, 100_000);
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
      return mapper.valueToTree(Map.of("count", 0));
    });

    started.await();
    TaskStatusResponse afterCancel = controller.cancel(id.toString());
    assertThat(afterCancel.status()).isIn("RUNNING", "CANCELLED");

    awaitTerminal(id);
    assertThat(registry.find(id).orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
  }
}
