package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.request.OnboardPlanRequest;
import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.reminiscence.ReminiscenceService;
import dev.alvo.pieria.task.TaskCancelledException;
import dev.alvo.pieria.task.TaskLane;
import dev.alvo.pieria.task.TaskProgress;
import dev.alvo.pieria.task.TaskRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs a composite onboarding request through at most two concurrent, source-sequential lanes. */
@Service
public class OnboardingPlanService {

  private static final Logger log = LoggerFactory.getLogger(OnboardingPlanService.class);

  private final OnboardingService onboarding;
  private final ReminiscenceService reminiscence;
  private final TaskRegistry tasks;
  private final ObjectMapper objectMapper;

  public OnboardingPlanService(OnboardingService onboarding, ReminiscenceService reminiscence,
                               TaskRegistry tasks, ObjectMapper objectMapper) {
    this.onboarding = onboarding;
    this.reminiscence = reminiscence;
    this.tasks = tasks;
    this.objectMapper = objectMapper;
  }

  public OnboardPlanResult ingest(String profile, OnboardPlanRequest request,
                                  TaskProgress progress) {
    List<SourceSpec> specs = request.sources();
    List<IndexedSpec> contentSpecs = new ArrayList<>();
    List<IndexedSpec> codeSpecs = new ArrayList<>();
    for (int i = 0; i < specs.size(); i++) {
      IndexedSpec indexed = new IndexedSpec(i, specs.get(i));
      (indexed.spec() instanceof SourceSpec.SourceCode ? codeSpecs : contentSpecs).add(indexed);
    }

    OnboardResult[] resultsByPosition = new OnboardResult[specs.size()];
    OnboardError[] errorsByPosition = new OnboardError[specs.size()];
    TaskLane content = contentSpecs.isEmpty() ? null : progress.lane("content");
    TaskLane code = codeSpecs.isEmpty() ? null : progress.lane("code");

    runLanes(profile, specs.size(), contentSpecs, codeSpecs, content, code,
      resultsByPosition, errorsByPosition, progress);
    progress.checkCancelled();

    long graphCandidates = request.graphEnrichmentEnabled()
      ? reminiscence.countOnboardingOrphans(profile) : 0L;
    String graphTaskId = null;
    if (request.graphEnrichmentEnabled() && graphCandidates > 0) {
      UUID submitted = tasks.submit("onboard-graph", profile, childProgress -> {
        TaskLane graph = childProgress.lane("graph");
        graph.start();
        var result = reminiscence.adoptOnboardingOrphans(profile, graph);
        graph.complete();
        return objectMapper.valueToTree(result);
      });
      graphTaskId = submitted.toString();
    }

    List<OnboardResult> results = Arrays.stream(resultsByPosition).filter(java.util.Objects::nonNull).toList();
    List<OnboardError> errors = Arrays.stream(errorsByPosition).filter(java.util.Objects::nonNull).toList();
    return aggregate(results, graphTaskId, graphCandidates, errors);
  }

  private void runLanes(String profile, int sourceCount,
                        List<IndexedSpec> contentSpecs, List<IndexedSpec> codeSpecs,
                        TaskLane content, TaskLane code,
                        OnboardResult[] results, OnboardError[] errors, TaskProgress progress) {
    int laneCount = (content == null ? 0 : 1) + (code == null ? 0 : 1);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(executor);
    List<Future<Void>> futures = new ArrayList<>(laneCount);
    boolean completed = false;
    try {
      if (content != null) {
        futures.add(completion.submit(() -> {
          runContentLane(profile, sourceCount, contentSpecs, content, results, errors);
          return null;
        }));
      }
      if (code != null) {
        futures.add(completion.submit(() -> {
          runCodeLane(profile, sourceCount, codeSpecs, content, code, results, errors);
          return null;
        }));
      }
      for (int i = 0; i < laneCount; i++) {
        completion.take().get();
      }
      completed = true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TaskCancelledException();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof TaskCancelledException) {
        throw (TaskCancelledException) cause;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(cause);
    } finally {
      if (!completed) {
        futures.forEach(future -> future.cancel(true));
      }
      executor.shutdownNow();
    }
    progress.checkCancelled();
  }

  private void runContentLane(String profile, int sourceCount, List<IndexedSpec> specs,
                              TaskLane lane, OnboardResult[] results, OnboardError[] errors) {
    Instant started = Instant.now();
    log.info("onboard content lane started ({} sources)", specs.size());
    lane.start();
    for (IndexedSpec indexed : specs) {
      executeSource(profile, sourceCount, indexed, lane, results, errors, true);
    }
    lane.complete();
    log.info("onboard content lane completed in {} ms", Duration.between(started, Instant.now()).toMillis());
  }

  private void runCodeLane(String profile, int sourceCount, List<IndexedSpec> specs,
                           TaskLane content, TaskLane lane,
                           OnboardResult[] results, OnboardError[] errors) {
    Instant started = Instant.now();
    log.info("onboard code lane started ({} sources)", specs.size());
    lane.start();
    List<IndexedWork> pending = new ArrayList<>();
    for (IndexedSpec indexed : specs) {
      OnboardingWork work = beginSource(profile, sourceCount, indexed, lane, errors);
      if (work != null) {
        pending.add(new IndexedWork(indexed, work));
      }
    }
    if (content != null) {
      lane.waiting("waiting for content");
      Instant waiting = Instant.now();
      log.info("onboard code lane waiting for content before summaries");
      content.awaitCompletion();
      log.info("onboard code lane content wait completed in {} ms",
        Duration.between(waiting, Instant.now()).toMillis());
    }
    for (IndexedWork indexed : pending) {
      finishSource(sourceCount, indexed, lane, results, errors);
    }
    lane.complete();
    log.info("onboard code lane completed in {} ms", Duration.between(started, Instant.now()).toMillis());
  }

  private void executeSource(String profile, int sourceCount, IndexedSpec indexed, TaskLane lane,
                             OnboardResult[] results, OnboardError[] errors, boolean finishImmediately) {
    OnboardingWork work = beginSource(profile, sourceCount, indexed, lane, errors);
    if (work != null && finishImmediately) {
      finishSource(sourceCount, new IndexedWork(indexed, work), lane, results, errors);
    }
  }

  private OnboardingWork beginSource(String profile, int sourceCount, IndexedSpec indexed,
                                     TaskLane lane, OnboardError[] errors) {
    String prefix = prefix(indexed, sourceCount);
    lane.onPhase(prefix, 0, 1);
    try {
      return onboarding.begin(profile, indexed.spec(), prefixed(lane, prefix));
    } catch (TaskCancelledException | VirtualMachineError e) {
      throw e;
    } catch (Throwable failure) {
      recordFailure(indexed, sourceCount, failure, errors);
      lane.onPhase(prefix, 1, 1);
      return null;
    }
  }

  private void finishSource(int sourceCount, IndexedWork indexed, TaskLane lane,
                            OnboardResult[] results, OnboardError[] errors) {
    String prefix = prefix(indexed.indexed(), sourceCount);
    try {
      results[indexed.indexed().position()] = indexed.work().finish(prefixed(lane, prefix));
    } catch (TaskCancelledException | VirtualMachineError e) {
      throw e;
    } catch (Throwable failure) {
      recordFailure(indexed.indexed(), sourceCount, failure, errors);
    }
    lane.onPhase(prefix, 1, 1);
  }

  private static IngestProgressListener prefixed(TaskLane lane, String prefix) {
    return (phase, done, total) -> lane.onPhase(prefix + ": " + phase, done, total);
  }

  private static String prefix(IndexedSpec indexed, int sourceCount) {
    return "source " + (indexed.position() + 1) + "/" + sourceCount + " " + sourceType(indexed.spec());
  }

  private static void recordFailure(IndexedSpec indexed, int sourceCount, Throwable failure,
                                    OnboardError[] errors) {
    int sourceNumber = indexed.position() + 1;
    String type = sourceType(indexed.spec());
    OnboardError error = OnboardError.from(sourceNumber, type, failure);
    errors[indexed.position()] = error;
    log.warn("onboard source {}/{} {} failed; continuing with remaining sources: {}",
      sourceNumber, sourceCount, type, error.message(), failure);
  }

  private static OnboardPlanResult aggregate(List<OnboardResult> results, String graphTaskId,
                                             long graphCandidates, List<OnboardError> errors) {
    int documents = results.stream().mapToInt(OnboardResult::documents).sum();
    int memories = results.stream().mapToInt(OnboardResult::memoriesStored).sum();
    int skipped = results.stream().map(OnboardResult::documentsSkipped)
      .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
    int deferred = results.stream().mapToInt(OnboardResult::graphDeferred).sum();
    int symbols = sumOptional(results, OnboardResult::symbols);
    int edges = sumOptional(results, OnboardResult::edges);
    int summaries = sumOptional(results, OnboardResult::summariesStored);
    return new OnboardPlanResult(results, documents, memories, skipped, deferred, symbols, edges,
      summaries, graphTaskId, graphCandidates, errors);
  }

  private static int sumOptional(List<OnboardResult> results,
                                 java.util.function.Function<OnboardResult, Integer> value) {
    return results.stream().map(value).filter(java.util.Objects::nonNull)
      .mapToInt(Integer::intValue).sum();
  }

  private static String sourceType(SourceSpec spec) {
    return switch (spec) {
      case SourceSpec.Markdown ignored -> "markdown";
      case SourceSpec.SourceCode ignored -> "source-code";
      case SourceSpec.Web ignored -> "web";
      case SourceSpec.Pdf ignored -> "pdf";
      case SourceSpec.Text ignored -> "text";
    };
  }

  private record IndexedSpec(int position, SourceSpec spec) {}
  private record IndexedWork(IndexedSpec indexed, OnboardingWork work) {}
}
