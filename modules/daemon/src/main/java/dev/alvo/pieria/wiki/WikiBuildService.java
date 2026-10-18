package dev.alvo.pieria.wiki;

import dev.alvo.pieria.api.response.WikiResponse;
import dev.alvo.pieria.task.TaskCancelledException;
import dev.alvo.pieria.task.TaskLane;
import dev.alvo.pieria.task.TaskProgress;
import dev.alvo.pieria.task.TaskRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds a profile's whole wiki as one background task. Projection already runs continuously and
 * needs no model, so a build waits for it to catch up, then queues an article for every topic and
 * entity page without one (and, for {@code stale}, every outdated one), and follows those jobs
 * until the wiki worker has written them. Articles are generated one at a time daemon-wide, so a
 * large profile takes a long time; cancelling withdraws the jobs no worker has started.
 */
@Service
public class WikiBuildService {

  static final Duration POLL = Duration.ofSeconds(3);
  static final Duration PROJECTION_DEADLINE = Duration.ofHours(6);
  private static final Set<String> TERMINAL = Set.of("succeeded", "failed", "obsolete");

  private final WikiService wiki;
  private final TaskRegistry tasks;

  public WikiBuildService(WikiService wiki, TaskRegistry tasks) {
    this.wiki = wiki;
    this.tasks = tasks;
  }

  /** Starts the build and returns its task id; 409 when the profile's wiki is disabled. */
  public UUID start(String profileName, String scope) {
    if (scope != null && !scope.equals("missing") && !scope.equals("stale")) {
      throw new WikiException(400, "invalid_scope");
    }
    String profileId = wiki.profile(profileName);
    if (!wiki.settings(profileId).enabled()) {
      throw new WikiException(409, "wiki_disabled");
    }
    boolean includeStale = "stale".equals(scope);
    return tasks.submit("wiki-build", profileName, progress -> run(profileId, includeStale, progress));
  }

  JsonNode run(String profileId, boolean includeStale, TaskProgress progress) {
    TaskLane projection = progress.lane("projection");
    TaskLane articles = progress.lane("articles");
    awaitProjection(profileId, projection);

    List<String> plan = wiki.buildPlan(profileId, includeStale);
    List<String> jobs = new ArrayList<>();
    int ready = 0;
    int insufficient = 0;
    try {
      for (int i = 0; i < plan.size(); i++) {
        articles.onPhase("queueing", i, plan.size());
        WikiResponse.Evaluation evaluation = enqueue(profileId, plan.get(i), includeStale ? "refresh" : "initial", articles);
        if (evaluation.job() != null) jobs.add(evaluation.job().id());
        else if ("insufficient_sources".equals(evaluation.reason())) insufficient++;
        else ready++;
      }
      Set<String> done = new HashSet<>();
      int succeeded = 0;
      int failed = 0;
      while (done.size() < jobs.size()) {
        for (String job : jobs) {
          if (done.contains(job)) continue;
          String status = wiki.jobStatus(profileId, job);
          if (TERMINAL.contains(status)) {
            done.add(job);
            if (status.equals("succeeded")) succeeded++;
            else if (status.equals("failed")) failed++;
          }
        }
        articles.onPhase("writing", done.size(), jobs.size());
        if (done.size() < jobs.size()) sleep(POLL, progress);
      }
      articles.complete();
      return JsonNodeFactory.instance.objectNode()
        .put("considered", plan.size())
        .put("enqueued", jobs.size())
        .put("published", succeeded)
        .put("failed", failed)
        .put("upToDate", ready)
        .put("insufficient", insufficient);
    } catch (TaskCancelledException e) {
      wiki.withdrawQueued(profileId, jobs);
      throw e;
    }
  }

  private void awaitProjection(String profileId, TaskLane lane) {
    long deadline = System.nanoTime() + PROJECTION_DEADLINE.toNanos();
    while (!wiki.projectionReady(profileId)) {
      long[] position = wiki.projectionProgress(profileId);
      lane.onPhase("catching up", (int) Math.min(Integer.MAX_VALUE, position[0]), (int) Math.min(Integer.MAX_VALUE, position[1]));
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("wiki projection did not catch up within " + PROJECTION_DEADLINE.toHours() + " hours");
      }
      sleep(POLL, null);
    }
    lane.complete();
  }

  /** Queues one article, waiting for room when the daemon-wide queue is full. */
  private WikiResponse.Evaluation enqueue(String profileId, String pageId, String intent, TaskLane lane) {
    while (true) {
      try {
        return wiki.evaluateForBuild(profileId, pageId, intent);
      } catch (WikiException e) {
        if (e.status() != 429) throw e;
        lane.waiting("queue full");
        sleep(POLL, null);
      }
    }
  }

  private static void sleep(Duration duration, TaskProgress progress) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TaskCancelledException();
    }
    if (progress != null) progress.checkCancelled();
  }
}
