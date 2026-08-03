package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.request.OnboardPlanRequest;
import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.api.response.OnboardError;
import dev.alvo.pieria.api.response.OnboardPlanResult;
import dev.alvo.pieria.api.response.OnboardResult;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.reminiscence.ReminiscenceService;
import dev.alvo.pieria.task.TaskLaneState;
import dev.alvo.pieria.task.TaskRegistry;
import dev.alvo.pieria.task.TaskSnapshot;
import dev.alvo.pieria.task.TaskStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnboardingPlanServiceTests {

  @Test
  void mixedLanesOverlapStaySequentialAndSummariesWaitForContent() throws Exception {
    CountDownLatch codeIndexed = new CountDownLatch(2);
    CountDownLatch releaseContent = new CountDownLatch(1);
    CountDownLatch contentFinished = new CountDownLatch(1);
    AtomicBoolean summaryBeforeContent = new AtomicBoolean();
    List<String> codeOrder = Collections.synchronizedList(new ArrayList<>());

    OnboardingSource<SourceSpec.Markdown> content = new OnboardingSource<>() {
      public Class<SourceSpec.Markdown> specType() { return SourceSpec.Markdown.class; }
      public OnboardingWork begin(String profile, SourceSpec.Markdown spec, IngestProgressListener progress) {
        await(releaseContent);
        contentFinished.countDown();
        return OnboardingWork.completed(OnboardResult.content("markdown", 1, 1, 0));
      }
    };
    OnboardingSource<SourceSpec.SourceCode> code = new OnboardingSource<>() {
      public Class<SourceSpec.SourceCode> specType() { return SourceSpec.SourceCode.class; }
      public OnboardingWork begin(String profile, SourceSpec.SourceCode spec, IngestProgressListener progress) {
        codeOrder.add("index:" + spec.root());
        codeIndexed.countDown();
        return finishProgress -> {
          summaryBeforeContent.set(contentFinished.getCount() != 0);
          codeOrder.add("summary:" + spec.root());
          return OnboardResult.code(1, 1, 1, 0, 1);
        };
      }
    };

    TaskRegistry graphTasks = mock(TaskRegistry.class);
    ReminiscenceService reminiscence = mock(ReminiscenceService.class);
    OnboardingPlanService service = service(List.of(content, code), reminiscence, graphTasks);
    TaskRegistry host = new TaskRegistry();
    AtomicReference<OnboardPlanResult> result = new AtomicReference<>();
    UUID id = submit(host, service, new OnboardPlanRequest(List.of(
      sourceCode("c1"), new SourceSpec.Markdown("m", false, null, null), sourceCode("c2")), false), result);

    assertThat(codeIndexed.await(2, TimeUnit.SECONDS)).isTrue();
    TaskSnapshot waiting = awaitLaneState(host, id, "code", TaskLaneState.WAITING);
    assertThat(waiting.lanes()).extracting(lane -> lane.name()).containsExactly("content", "code");
    assertThat(waiting.lanes().stream().filter(lane -> lane.name().equals("code")).findFirst().orElseThrow().phase())
      .isEqualTo("waiting for content");
    releaseContent.countDown();

    assertThat(awaitTerminal(host, id).status()).isEqualTo(TaskStatus.SUCCEEDED);
    assertThat(summaryBeforeContent).isFalse();
    assertThat(codeOrder).containsExactly("index:c1", "index:c2", "summary:c1", "summary:c2");
    assertThat(result.get().sources()).extracting(OnboardResult::sourceType)
      .containsExactly("source-code", "markdown", "source-code");
  }

  @Test
  void sourceFailuresDoNotStopLanesAndResultsAndErrorsKeepRequestOrder() throws Exception {
    List<String> contentOrder = Collections.synchronizedList(new ArrayList<>());
    List<String> codeOrder = Collections.synchronizedList(new ArrayList<>());
    OnboardingSource<SourceSpec.Markdown> markdown = contentSource(SourceSpec.Markdown.class,
      spec -> ((SourceSpec.Markdown) spec).root(), contentOrder);
    OnboardingSource<SourceSpec.Text> text = new OnboardingSource<>() {
      public Class<SourceSpec.Text> specType() { return SourceSpec.Text.class; }
      public OnboardingWork begin(String profile, SourceSpec.Text spec, IngestProgressListener progress) {
        contentOrder.add(spec.root());
        throw new IllegalStateException("broken text");
      }
    };
    OnboardingSource<SourceSpec.SourceCode> code = new OnboardingSource<>() {
      public Class<SourceSpec.SourceCode> specType() { return SourceSpec.SourceCode.class; }
      public OnboardingWork begin(String profile, SourceSpec.SourceCode spec, IngestProgressListener progress) {
        codeOrder.add(spec.root());
        int documents = Integer.parseInt(spec.root());
        return ignored -> OnboardResult.code(documents, documents, 1, 0, 0);
      }
    };
    OnboardingPlanService service = service(List.of(markdown, text, code),
      mock(ReminiscenceService.class), mock(TaskRegistry.class));
    TaskRegistry host = new TaskRegistry();
    AtomicReference<OnboardPlanResult> result = new AtomicReference<>();

    UUID id = submit(host, service, new OnboardPlanRequest(List.of(
      sourceCode("10"), new SourceSpec.Markdown("m1", false, null, null),
      new SourceSpec.Text("bad", null, null), sourceCode("20"),
      new SourceSpec.Markdown("m2", false, null, null)), false), result);

    assertThat(awaitTerminal(host, id).status()).isEqualTo(TaskStatus.SUCCEEDED);
    assertThat(contentOrder).containsExactly("m1", "bad", "m2");
    assertThat(codeOrder).containsExactly("10", "20");
    assertThat(result.get().sources()).extracting(OnboardResult::documents).containsExactly(10, 1, 20, 1);
    assertThat(result.get().errors()).containsExactly(
      new OnboardError(3, "text", "IllegalStateException", "broken text"));
  }

  @Test
  void contentOnlyAndCodeOnlyUseTheSameLaneScheduler() throws Exception {
    OnboardingSource<SourceSpec.Markdown> markdown = contentSource(SourceSpec.Markdown.class,
      ignored -> "markdown", new ArrayList<>());
    OnboardingSource<SourceSpec.SourceCode> code = new OnboardingSource<>() {
      public Class<SourceSpec.SourceCode> specType() { return SourceSpec.SourceCode.class; }
      public OnboardingWork begin(String profile, SourceSpec.SourceCode spec, IngestProgressListener progress) {
        return ignored -> OnboardResult.code(1, 1, 1, 0, 0);
      }
    };
    OnboardingPlanService service = service(List.of(markdown, code),
      mock(ReminiscenceService.class), mock(TaskRegistry.class));

    TaskRegistry contentHost = new TaskRegistry();
    UUID contentId = submit(contentHost, service, new OnboardPlanRequest(List.of(
      new SourceSpec.Markdown("m", false, null, null)), false), new AtomicReference<>());
    assertThat(awaitTerminal(contentHost, contentId).lanes()).extracting(lane -> lane.name())
      .containsExactly("content");

    TaskRegistry codeHost = new TaskRegistry();
    UUID codeId = submit(codeHost, service, new OnboardPlanRequest(List.of(sourceCode("c")), false),
      new AtomicReference<>());
    assertThat(awaitTerminal(codeHost, codeId).lanes()).extracting(lane -> lane.name())
      .containsExactly("code");
  }

  @Test
  void cancellationInterruptsBothLanesAndSuppressesGraphSubmission() throws Exception {
    CountDownLatch bothStarted = new CountDownLatch(2);
    AtomicBoolean contentInterrupted = new AtomicBoolean();
    AtomicBoolean codeInterrupted = new AtomicBoolean();
    OnboardingSource<SourceSpec.Markdown> content = blockingSource(
      SourceSpec.Markdown.class, bothStarted, contentInterrupted);
    OnboardingSource<SourceSpec.SourceCode> code = blockingSource(
      SourceSpec.SourceCode.class, bothStarted, codeInterrupted);
    ReminiscenceService reminiscence = mock(ReminiscenceService.class);
    TaskRegistry graphTasks = mock(TaskRegistry.class);
    OnboardingPlanService service = service(List.of(content, code), reminiscence, graphTasks);
    TaskRegistry host = new TaskRegistry();
    UUID id = submit(host, service, new OnboardPlanRequest(List.of(
      new SourceSpec.Markdown("m", false, null, null), sourceCode("c")), true), new AtomicReference<>());

    assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
    host.cancel(id);
    TaskSnapshot terminal = awaitTerminal(host, id);

    assertThat(terminal.status()).isEqualTo(TaskStatus.CANCELLED);
    assertThat(terminal.lanes()).extracting(lane -> lane.state())
      .containsOnly(TaskLaneState.CANCELLED);
    awaitInterrupted(contentInterrupted, "content");
    awaitInterrupted(codeInterrupted, "code");
    verify(reminiscence, never()).countOnboardingOrphans(any());
    verify(graphTasks, never()).submit(any(), any(), any());
  }

  @Test
  void fatalJvmFailureCancelsSiblingAndGraphCountingWaitsForSuccessfulSummary() throws Exception {
    CountDownLatch contentStarted = new CountDownLatch(1);
    AtomicBoolean contentInterrupted = new AtomicBoolean();
    OnboardingSource<SourceSpec.Markdown> content = blockingSource(
      SourceSpec.Markdown.class, contentStarted, contentInterrupted);
    OnboardingSource<SourceSpec.SourceCode> fatal = new OnboardingSource<>() {
      public Class<SourceSpec.SourceCode> specType() { return SourceSpec.SourceCode.class; }
      public OnboardingWork begin(String profile, SourceSpec.SourceCode spec, IngestProgressListener progress) {
        await(contentStarted);
        throw new OutOfMemoryError("fatal");
      }
    };
    ReminiscenceService reminiscence = mock(ReminiscenceService.class);
    TaskRegistry graphTasks = mock(TaskRegistry.class);
    OnboardingPlanService service = service(List.of(content, fatal), reminiscence, graphTasks);
    TaskRegistry host = new TaskRegistry();
    UUID id = submit(host, service, new OnboardPlanRequest(List.of(
      new SourceSpec.Markdown("m", false, null, null), sourceCode("c")), true), new AtomicReference<>());

    assertThat(awaitTerminal(host, id).status()).isEqualTo(TaskStatus.FAILED);
    awaitInterrupted(contentInterrupted, "content");
    verify(reminiscence, never()).countOnboardingOrphans(any());

    AtomicBoolean summaryFinished = new AtomicBoolean();
    OnboardingSource<SourceSpec.SourceCode> successful = new OnboardingSource<>() {
      public Class<SourceSpec.SourceCode> specType() { return SourceSpec.SourceCode.class; }
      public OnboardingWork begin(String profile, SourceSpec.SourceCode spec, IngestProgressListener progress) {
        return ignored -> {
          summaryFinished.set(true);
          return OnboardResult.code(1, 1, 1, 0, 1);
        };
      }
    };
    ReminiscenceService after = mock(ReminiscenceService.class);
    AtomicBoolean countedAfterSummary = new AtomicBoolean();
    when(after.countOnboardingOrphans("p")).thenAnswer(ignored -> {
      countedAfterSummary.set(summaryFinished.get());
      return 0L;
    });
    OnboardingPlanService successfulService = service(List.of(successful), after, mock(TaskRegistry.class));
    TaskRegistry successfulHost = new TaskRegistry();
    UUID successfulId = submit(successfulHost, successfulService,
      new OnboardPlanRequest(List.of(sourceCode("c")), true), new AtomicReference<>());
    assertThat(awaitTerminal(successfulHost, successfulId).status()).isEqualTo(TaskStatus.SUCCEEDED);
    assertThat(countedAfterSummary).isTrue();
  }

  private static OnboardingPlanService service(List<OnboardingSource<?>> sources,
                                                ReminiscenceService reminiscence, TaskRegistry graphTasks) {
    return new OnboardingPlanService(new OnboardingService(sources), reminiscence,
      graphTasks, new ObjectMapper());
  }

  private static UUID submit(TaskRegistry host, OnboardingPlanService service, OnboardPlanRequest request,
                             AtomicReference<OnboardPlanResult> result) {
    return host.submit("onboard", "p", progress -> {
      result.set(service.ingest("p", request, progress));
      return JsonNodeFactory.instance.nullNode();
    });
  }

  private static TaskSnapshot awaitTerminal(TaskRegistry registry, UUID id) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      TaskSnapshot snapshot = registry.find(id).orElseThrow();
      if (snapshot.status() != TaskStatus.RUNNING) {
        return snapshot;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("task did not finish");
  }

  /**
   * Wait for a lane worker to record its interrupt. Cancellation marks the task terminal from the
   * orchestrating thread ({@code TaskCancelledException}) and only <em>delivers</em> the interrupt
   * to the lane workers afterwards, in the {@code finally} block, without joining them — so a
   * terminal snapshot does not imply the workers have unwound yet. Asserting the flag directly is
   * therefore racy; poll for it instead.
   */
  private static void awaitInterrupted(AtomicBoolean flag, String lane) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (flag.get()) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(lane + " lane was not interrupted");
  }

  private static TaskSnapshot awaitLaneState(TaskRegistry registry, UUID id, String name,
                                             TaskLaneState state) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      TaskSnapshot snapshot = registry.find(id).orElseThrow();
      if (snapshot.lanes().stream().anyMatch(lane -> lane.name().equals(name) && lane.state() == state)) {
        return snapshot;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("lane did not reach " + state);
  }

  private static SourceSpec.SourceCode sourceCode(String root) {
    return new SourceSpec.SourceCode(root, false, false, null);
  }

  private static <S extends SourceSpec> OnboardingSource<S> contentSource(
    Class<S> type, java.util.function.Function<SourceSpec, String> name, List<String> order) {
    return new OnboardingSource<>() {
      public Class<S> specType() { return type; }
      public OnboardingWork begin(String profile, S spec, IngestProgressListener progress) {
        order.add(name.apply(spec));
        progress.onPhase("extract", 1, 1);
        return OnboardingWork.completed(OnboardResult.content(name.apply(spec), 1, 1, 0));
      }
    };
  }

  private static <S extends SourceSpec> OnboardingSource<S> blockingSource(
    Class<S> type, CountDownLatch started, AtomicBoolean interrupted) {
    return new OnboardingSource<>() {
      public Class<S> specType() { return type; }
      public OnboardingWork begin(String profile, S spec, IngestProgressListener progress) {
        started.countDown();
        try {
          new CountDownLatch(1).await();
          throw new AssertionError("unreachable");
        } catch (InterruptedException e) {
          interrupted.set(true);
          Thread.currentThread().interrupt();
          throw new dev.alvo.pieria.task.TaskCancelledException();
        }
      }
    };
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for test barrier");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new dev.alvo.pieria.task.TaskCancelledException();
    }
  }
}
