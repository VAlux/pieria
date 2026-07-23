package dev.alvo.pieria.task;

import dev.alvo.pieria.model.ModelUnavailableException;
import dev.alvo.pieria.task.TaskRegistry.CancelOutcome;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TaskRegistry}: submitted work runs on a background thread, its terminal state and
 * result become observable through {@link TaskRegistry#find}/{@link TaskRegistry#all}, failures are
 * classified so a polling client can map them to the right outcome, and {@link TaskRegistry#cancel}
 * stops a running task cooperatively.
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
    UUID id = registry.submit("ingest", "p", progress -> {
      progress.lane("ingest").onPhase("extract", 1, 1);
      return mapper.valueToTree(Map.of("count", 3));
    });

    TaskSnapshot s = awaitTerminal(id);
    assertThat(s.status()).isEqualTo(TaskStatus.SUCCEEDED);
    assertThat(s.result().get("count").asInt()).isEqualTo(3);
    assertThat(s.errorKind()).isNull();
    assertThat(s.lanes()).singleElement().satisfies(lane -> {
      assertThat(lane.name()).isEqualTo("ingest");
      assertThat(lane.state()).isEqualTo(TaskLaneState.COMPLETED);
    });
  }

  @Test
  void runtimeFailureIsRecordedAsFailure() throws InterruptedException {
    UUID id = registry.submit("ingest", "p", progress -> {
      throw new IllegalStateException("boom");
    });

    TaskSnapshot s = awaitTerminal(id);
    assertThat(s.status()).isEqualTo(TaskStatus.FAILED);
    assertThat(s.errorKind()).isEqualTo("failure");
    assertThat(s.errorMessage()).contains("boom");
  }

  @Test
  void errorFromWorkIsRecordedAsFailureNotStrandedRunning() throws InterruptedException {
    // A non-RuntimeException Throwable (e.g. a GraalVM MissingReflectionRegistrationError from
    // serializing an unregistered result record) must still drive the task to a terminal state,
    // never leave it stuck RUNNING with the Throwable swallowed by the unread Future.
    UUID id = registry.submit("ingest", "p", progress -> {
      throw new AssertionError("simulated Error escaping the work body");
    });

    TaskSnapshot s = awaitTerminal(id);
    assertThat(s.status()).isEqualTo(TaskStatus.FAILED);
    assertThat(s.errorKind()).isEqualTo("failure");
    assertThat(s.errorMessage()).contains("simulated Error");
  }

  @Test
  void modelUnavailableIsClassified() throws InterruptedException {
    UUID id = registry.submit("ingest", "p", progress -> {
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

  @Test
  void listExposesMetadataForFinishedTask() throws InterruptedException {
    UUID id = registry.submit("onboard", "pieria", progress -> mapper.valueToTree(Map.of("count", 0)));
    awaitTerminal(id);

    var info = registry.all().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    assertThat(info.kind()).isEqualTo("onboard");
    assertThat(info.profile()).isEqualTo("pieria");
    assertThat(info.snapshot().status()).isEqualTo(TaskStatus.SUCCEEDED);
  }

  @Test
  void cancelStopsRunningTaskAtNextTick() throws InterruptedException {
    CountDownLatch started = new CountDownLatch(1);
    UUID id = registry.submit("ingest", "p", progress -> {
      started.countDown();
      TaskLane lane = progress.lane("ingest");
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
    assertThat(registry.cancel(id)).isEqualTo(CancelOutcome.CANCELLED);

    TaskSnapshot s = awaitTerminal(id);
    assertThat(s.status()).isEqualTo(TaskStatus.CANCELLED);
    assertThat(s.errorKind()).isEqualTo("cancelled");
  }

  @Test
  void cancelUnknownTaskReportsNotFound() {
    assertThat(registry.cancel(UUID.randomUUID())).isEqualTo(CancelOutcome.NOT_FOUND);
  }

  @Test
  void cancelFinishedTaskReportsAlreadyTerminal() throws InterruptedException {
    UUID id = registry.submit("ingest", "p", progress -> mapper.valueToTree(Map.of("count", 0)));
    awaitTerminal(id);
    assertThat(registry.cancel(id)).isEqualTo(CancelOutcome.ALREADY_TERMINAL);
  }

  @Test
  void laneTransitionsThroughQueuedRunningWaitingAndCompleted() throws Exception {
    CountDownLatch queued = new CountDownLatch(1);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch wait = new CountDownLatch(1);
    CountDownLatch waiting = new CountDownLatch(1);
    CountDownLatch complete = new CountDownLatch(1);
    UUID id = registry.submit("graph", "p", progress -> {
      TaskLane lane = progress.lane("graph");
      queued.countDown();
      await(start);
      lane.start();
      running.countDown();
      await(wait);
      lane.waiting("dependency");
      waiting.countDown();
      await(complete);
      lane.complete();
      return tools.jackson.databind.node.JsonNodeFactory.instance.nullNode();
    });

    assertThat(queued.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(registry.find(id).orElseThrow().lanes().getFirst().state()).isEqualTo(TaskLaneState.QUEUED);
    start.countDown();
    assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(registry.find(id).orElseThrow().lanes().getFirst().state()).isEqualTo(TaskLaneState.RUNNING);
    wait.countDown();
    assertThat(waiting.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(registry.find(id).orElseThrow().lanes().getFirst()).satisfies(lane -> {
      assertThat(lane.state()).isEqualTo(TaskLaneState.WAITING);
      assertThat(lane.phase()).isEqualTo("dependency");
    });
    complete.countDown();
    assertThat(awaitTerminal(id).lanes().getFirst().state()).isEqualTo(TaskLaneState.COMPLETED);
  }

  @Test
  void taskFailureMarksEveryUnfinishedLaneFailed() throws InterruptedException {
    UUID id = registry.submit("onboard", "p", progress -> {
      progress.lane("content").start();
      progress.lane("code");
      throw new IllegalStateException("boom");
    });

    assertThat(awaitTerminal(id).lanes()).extracting(TaskLaneSnapshot::state)
      .containsExactly(TaskLaneState.FAILED, TaskLaneState.FAILED);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TaskCancelledException();
    }
  }
}
