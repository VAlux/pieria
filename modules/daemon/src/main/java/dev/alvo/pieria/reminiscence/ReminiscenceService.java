package dev.alvo.pieria.reminiscence;

import dev.alvo.pieria.api.response.ReminiscenceResult;
import dev.alvo.pieria.config.ReminiscenceProperties;
import dev.alvo.pieria.code.CodeIndexingService;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.task.TaskCancelledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Orphan adoption — the "replay" phase of the reminiscence process. Finds active, non-{@code TASK}
 * memories that carry no graph edges (typically {@code remember}-authored, stored with an empty
 * fragment) and retroactively runs the <em>same</em> graph extraction the ingest pipeline uses over
 * their content, attaching the resulting entities/edges so they join the entity-relation graph and
 * the graph retrieval channel.
 *
 * <p>Runs as a cancellable background task (see {@code ReminiscenceController}). It is model-heavy,
 * so extraction is batched <em>and</em> run concurrently per {@link ReminiscenceProperties}: the
 * stage is output-token bound, spending nearly all of its wall time decoding replies, so overlapping
 * calls — not larger batches — is what makes a large onboard finish in minutes rather than hours.
 * Only the model calls fan out; every store write stays on the calling thread.
 *
 * <p><b>Model-down is fail-closed.</b> {@code extractGraphAll} is fully degradable — a provider
 * failure returns empty fragments rather than throwing. Since {@link MemoryStore#attachGraph} stamps
 * {@code graph_adopted_at} unconditionally, running while the provider is unreachable would mark
 * every scanned memory as adopted-but-empty and never retry it. To prevent that poisoning, the run
 * pre-flights {@link ModelGateway#isModelProviderReachable()} and aborts with
 * {@link ModelUnavailableException} (which the task framework classifies as {@code model-unavailable})
 * before touching any memory.
 */
@Service
public class ReminiscenceService {

  private static final Logger log = LoggerFactory.getLogger(ReminiscenceService.class);

  private final MemoryStore store;
  private final ModelGateway modelGateway;
  private final ReminiscenceProperties properties;
  private static final List<String> ONBOARDING_SESSIONS =
    List.of(dev.alvo.pieria.onboarding.ContentIngestor.SESSION_ID,
      CodeIndexingService.CODE_SESSION, "pieria-code");

  public ReminiscenceService(MemoryStore store, ModelGateway modelGateway, ReminiscenceProperties properties) {
    this.store = store;
    this.modelGateway = modelGateway;
    this.properties = properties;
  }

  /**
   * Adopt every graph orphan in the profile. Progress is reported under the {@code "reminisce"} phase;
   * each sub-batch is a cooperative cancellation checkpoint (the listener throws when cancellation is
   * requested). Because each processed memory is stamped, a cancelled or failed run resumes where it
   * left off on the next invocation.
   */
  public ReminiscenceResult adoptOrphans(String profileName, IngestProgressListener progress) {
    return adoptOrphans(profileName, null, "reminisce", progress);
  }

  /** Adopt only memories produced by automatic onboarding sessions. */
  public ReminiscenceResult adoptOnboardingOrphans(String profileName, IngestProgressListener progress) {
    return adoptOrphans(profileName, ONBOARDING_SESSIONS, "onboard-graph", progress);
  }

  /** Cheap candidate count for the automatic onboarding child task. */
  public long countOnboardingOrphans(String profileName) {
    String profileId = store.getOrCreateProfile(profileName).id();
    return store.countGraphOrphans(profileId, ONBOARDING_SESSIONS);
  }

  /**
   * Cheap dry-run for the {@code /reminisce/orphans} endpoint: how many orphans a run would adopt,
   * via a plain store count (no model call). A {@code 404} when there is no such profile.
   */
  public long orphanCount(String profileName) {
    var profile = store.findProfile(profileName)
      .orElseThrow(() -> NotFoundException.profile(profileName));
    return store.countGraphOrphans(profile.id());
  }

  private ReminiscenceResult adoptOrphans(String profileName, List<String> sessions, String phase,
                                          IngestProgressListener progress) {
    if (!modelGateway.isModelProviderReachable()) {
      throw new ModelUnavailableException("model provider unreachable; skipping orphan adoption");
    }

    String profileId = store.getOrCreateProfile(profileName).id();
    long total = sessions == null
      ? store.countGraphOrphans(profileId)
      : store.countGraphOrphans(profileId, sessions);
    progress.onPhase(phase, 0, (int) Math.min(total, Integer.MAX_VALUE));
    if (total == 0) {
      return new ReminiscenceResult(0, 0, 0, 0);
    }

    Counts counts = new Counts();
    int totalTicks = (int) Math.min(total, Integer.MAX_VALUE);
    int parallelism = properties.parallelism();
    log.info("{} start profile={} orphans={} batchSize={} parallelism={}",
      phase, profileName, total, properties.batchSize(), parallelism);

    Semaphore gate = new Semaphore(parallelism);
    try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Memory> page;
      while (!(page = sessions == null
        ? store.findGraphOrphans(profileId, properties.scanPageSize())
        : store.findGraphOrphans(profileId, sessions, properties.scanPageSize())).isEmpty()) {
        adoptPage(profileId, phase, partition(page), gate, exec, counts, totalTicks, progress);
      }
    }

    log.info("{} profile={} scanned={} adopted={} entities={} edges={}",
      phase, profileName, counts.scanned, counts.adopted, counts.entities, counts.edges);
    return new ReminiscenceResult(counts.scanned, counts.adopted, counts.entities, counts.edges);
  }

  /**
   * Extract one page's batches concurrently, then attach the fragments in submission order.
   *
   * <p>Only the model calls fan out; {@link MemoryStore#attachGraph} is applied here on the calling
   * thread, so the daemon's single-writer invariant holds and progress still advances in a stable
   * order. Draining in submission order (rather than completion order) keeps the tick sequence
   * deterministic, which matters because {@code onPhase} is also the cancellation checkpoint — it
   * throws when the task is cancelled, and every batch already attached by then stays stamped, so the
   * next run resumes from exactly there.
   */
  private void adoptPage(String profileId, String phase, List<List<Memory>> batches, Semaphore gate,
                         ExecutorService exec, Counts counts, int totalTicks,
                         IngestProgressListener progress) {
    List<Future<List<GraphFragment>>> futures = new ArrayList<>(batches.size());
    for (List<Memory> batch : batches) {
      List<String> contents = batch.stream().map(Memory::content).toList();
      futures.add(exec.submit(() -> bounded(gate, () -> modelGateway.extractGraphAll(contents))));
    }

    try {
      for (int b = 0; b < batches.size(); b++) {
        List<Memory> batch = batches.get(b);
        List<GraphFragment> fragments = await(futures.get(b));
        for (int i = 0; i < batch.size(); i++) {
          Memory memory = batch.get(i);
          GraphFragment fragment = i < fragments.size() ? fragments.get(i) : GraphFragment.empty();
          store.attachGraph(profileId, memory.id(), fragment);
          counts.scanned++;
          if (!fragment.isEmpty()) {
            counts.adopted++;
            counts.entities += fragment.allEntities().size();
            counts.edges += fragment.triples().size();
          }
        }
        progress.onPhase(phase, Math.min(counts.scanned, totalTicks), totalTicks);
      }
    } catch (RuntimeException e) {
      // Cancellation or an unexpected failure: drop the batches that have not started yet so the
      // executor's close() does not first work through a page's worth of queued model calls.
      for (Future<List<GraphFragment>> pending : futures) {
        pending.cancel(true);
      }
      throw e;
    }
  }

  private static <T> T bounded(Semaphore gate, Supplier<T> call) throws InterruptedException {
    gate.acquire();
    try {
      return call.get();
    } finally {
      gate.release();
    }
  }

  private static List<GraphFragment> await(Future<List<GraphFragment>> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TaskCancelledException();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (e.getCause() instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("graph extraction failed", e.getCause());
    }
  }

  /** Mutable tallies threaded through the page loop. */
  private static final class Counts {
    int scanned;
    int adopted;
    int entities;
    int edges;
  }

  /**
   * Split a page of orphans into sub-batches capped by {@link ReminiscenceProperties#batchSize()} and
   * {@link ReminiscenceProperties#batchCharBudget()} — whichever bound is hit first, but always at
   * least one memory per sub-batch so a single over-budget memory still gets its own call.
   */
  private List<List<Memory>> partition(List<Memory> page) {
    int maxSize = Math.max(1, properties.batchSize());
    int charBudget = Math.max(1, properties.batchCharBudget());
    List<List<Memory>> batches = new ArrayList<>();
    List<Memory> current = new ArrayList<>();
    int currentChars = 0;
    for (Memory memory : page) {
      int len = memory.content() == null ? 0 : memory.content().length();
      boolean full = current.size() >= maxSize || (!current.isEmpty() && currentChars + len > charBudget);
      if (full) {
        batches.add(current);
        current = new ArrayList<>();
        currentChars = 0;
      }
      current.add(memory);
      currentChars += len;
    }
    if (!current.isEmpty()) {
      batches.add(current);
    }
    return batches;
  }
}
