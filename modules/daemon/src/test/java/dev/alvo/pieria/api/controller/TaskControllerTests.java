package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.TaskListResponse;
import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.api.response.TaskSummary;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.domain.error.ConflictException;
import dev.alvo.pieria.task.TaskRegistry;
import dev.alvo.pieria.task.TaskService;
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
  private final TaskController controller = new TaskController(new TaskService(registry));

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
      progress.lane("content").onPhase("extract", 1, 1);
      return mapper.valueToTree(Map.of("count", 2));
    });
    awaitTerminal(id);

    TaskListResponse list = controller.list(null);
    TaskSummary summary = list.tasks().stream()
      .filter(t -> t.id().equals(id.toString())).findFirst().orElseThrow();
    assertThat(summary.kind()).isEqualTo("onboard");
    assertThat(summary.profile()).isEqualTo("pieria");
    assertThat(summary.status()).isEqualTo("SUCCEEDED");
    assertThat(summary.startedAtEpochMs()).isPositive();
    assertThat(summary.finishedAtEpochMs()).isGreaterThanOrEqualTo(summary.startedAtEpochMs());
    assertThat(summary.resultSummary()).isEqualTo("2 memories stored");
    assertThat(summary.retryable()).isFalse();
    assertThat(summary.lanes()).singleElement().satisfies(lane -> {
      assertThat(lane.name()).isEqualTo("content");
      assertThat(lane.state()).isEqualTo("COMPLETED");
    });

    TaskStatusResponse status = controller.status(id.toString());
    assertThat(status.kind()).isEqualTo("onboard");
    assertThat(status.profile()).isEqualTo("pieria");
    assertThat(status.status()).isEqualTo("SUCCEEDED");
    assertThat(status.result().get("count").asInt()).isEqualTo(2);
    var wire = mapper.valueToTree(status);
    assertThat(wire.path("lanes").isArray()).isTrue();
    assertThat(wire.has("phase")).isFalse();
    assertThat(wire.has("done")).isFalse();
    assertThat(wire.has("total")).isFalse();
    assertThat(wire.has("phaseStartedAtEpochMs")).isFalse();
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
      var lane = progress.lane("ingest");
      for (int i = 0; i < 100_000; i++) {
        lane.onPhase("extract", i, 100_000);
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

  @Test
  void listFiltersByProfile() throws InterruptedException {
    UUID mine = registry.submit("ingest", "mine", progress -> mapper.valueToTree(Map.of("count", 1)));
    UUID theirs = registry.submit("ingest", "theirs", progress -> mapper.valueToTree(Map.of("count", 1)));
    awaitTerminal(mine);
    awaitTerminal(theirs);

    assertThat(controller.list("mine").tasks()).extracting(TaskSummary::id).containsExactly(mine.toString());
    assertThat(controller.list(null).tasks()).extracting(TaskSummary::id).contains(mine.toString(), theirs.toString());
  }

  @Test
  void retryRunsFailedWorkAgainAndRejectsOtherStates() throws InterruptedException {
    java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
    UUID failed = registry.submit("ingest", "p", progress -> {
      if (attempts.incrementAndGet() == 1) throw new IllegalStateException("database locked");
      return mapper.valueToTree(Map.of("count", 3));
    });
    awaitTerminal(failed);
    assertThat(controller.status(failed.toString()).retryable()).isTrue();

    UUID retried = UUID.fromString(controller.retry(failed.toString(), mapper.createObjectNode()).taskId());
    awaitTerminal(retried);
    TaskStatusResponse status = controller.status(retried.toString());
    assertThat(status.status()).isEqualTo("SUCCEEDED");
    assertThat(status.kind()).isEqualTo("ingest");
    assertThat(status.profile()).isEqualTo("p");
    assertThat(attempts.get()).isEqualTo(2);

    assertThatThrownBy(() -> controller.retry(retried.toString(), mapper.createObjectNode()))
      .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> controller.retry(UUID.randomUUID().toString(), mapper.createObjectNode()))
      .isInstanceOf(NotFoundException.class);
  }
}
