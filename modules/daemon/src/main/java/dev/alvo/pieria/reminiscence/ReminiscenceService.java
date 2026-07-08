package dev.alvo.pieria.reminiscence;

import dev.alvo.pieria.config.ReminiscenceProperties;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;
import dev.alvo.pieria.storage.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orphan adoption — the "replay" phase of the reminiscence process. Finds active, non-{@code TASK}
 * memories that carry no graph edges (typically {@code remember}-authored, stored with an empty
 * fragment) and retroactively runs the <em>same</em> graph extraction the ingest pipeline uses over
 * their content, attaching the resulting entities/edges so they join the entity-relation graph and
 * the graph retrieval channel.
 *
 * <p>Runs as a cancellable background task (see {@code ReminiscenceController}). It is model-heavy,
 * so extraction is batched per {@link ReminiscenceProperties}.
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
    if (!modelGateway.isModelProviderReachable()) {
      throw new ModelUnavailableException("model provider unreachable; skipping orphan adoption");
    }

    String profileId = store.getOrCreateProfile(profileName).id();
    long total = store.countGraphOrphans(profileId);
    progress.onPhase("reminisce", 0, (int) Math.min(total, Integer.MAX_VALUE));
    if (total == 0) {
      return new ReminiscenceResult(0, 0, 0, 0);
    }

    int scanned = 0;
    int adopted = 0;
    int entities = 0;
    int edges = 0;
    int totalTicks = (int) Math.min(total, Integer.MAX_VALUE);

    List<Memory> page;
    while (!(page = store.findGraphOrphans(profileId, properties.scanPageSize())).isEmpty()) {
      for (List<Memory> batch : partition(page)) {
        List<String> contents = batch.stream().map(Memory::content).toList();
        List<GraphFragment> fragments = modelGateway.extractGraphAll(contents);
        for (int i = 0; i < batch.size(); i++) {
          Memory memory = batch.get(i);
          GraphFragment fragment = i < fragments.size() ? fragments.get(i) : GraphFragment.empty();
          store.attachGraph(profileId, memory.id(), fragment);
          scanned++;
          if (!fragment.isEmpty()) {
            adopted++;
            entities += fragment.allEntities().size();
            edges += fragment.triples().size();
          }
        }
        progress.onPhase("reminisce", Math.min(scanned, totalTicks), totalTicks);
      }
    }

    log.info("reminisce profile={} scanned={} adopted={} entities={} edges={}",
      profileName, scanned, adopted, entities, edges);
    return new ReminiscenceResult(scanned, adopted, entities, edges);
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
