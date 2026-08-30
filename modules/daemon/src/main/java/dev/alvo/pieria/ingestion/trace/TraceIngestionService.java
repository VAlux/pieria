package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.storage.CodeIndexStore;
import dev.alvo.pieria.storage.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The write path for execution traces: deduplicate, redact, reject noise, then derive memories.
 *
 * <p>Deliberately <em>not</em> routed through {@code IngestionService}'s chunked extraction. A trace
 * is already structured, so chunking it into a transcript and asking a model to re-read it would pay
 * tokens to restate facts the payload already carries. What the two paths share is everything after
 * derivation: the same {@code MemoryStore.store} call, and therefore the same supersession,
 * graph persistence, and vectorization outbox.
 *
 * <p>Stage order is redact, filter, persist raw, derive events, derive recipes. Persisting raw rows
 * after the filter is deliberate: every successful {@code Read} stored as a {@code messages} row
 * would be pure noise in {@code MessageFtsChannel}.
 */
@Service
public class TraceIngestionService {

  private static final Logger log = LoggerFactory.getLogger(TraceIngestionService.class);

  /** Role used for the raw trace rows in {@code messages}. */
  private static final String TRACE_ROLE = "tool";

  private final MemoryStore store;
  private final TraceProperties properties;
  private final TraceRelevanceFilter relevanceFilter;
  private final TraceGraphBuilder graphBuilder;
  private final TraceCodeLinker codeLinker;
  private final TraceRecipeExtractor recipeExtractor;

  public TraceIngestionService(MemoryStore store,
                               CodeIndexStore codeIndexStore,
                               ModelGateway modelGateway,
                               TraceProperties properties,
                               PieriaProperties pieria) {
    this.store = store;
    this.properties = properties;
    this.relevanceFilter = new TraceRelevanceFilter(properties);
    this.graphBuilder = new TraceGraphBuilder(
      pieria.ingestion().maxGraphEntitiesPerMemory(), pieria.ingestion().maxGraphTriplesPerMemory());
    this.codeLinker = new TraceCodeLinker(codeIndexStore, properties.maxLinkedSymbols());
    this.recipeExtractor = new TraceRecipeExtractor(modelGateway, properties);
  }

  /** Ingest one batch of traces, returning the memories actually stored (empty when all deduped). */
  public List<Memory> ingest(String profileName, String sessionId, List<TraceEventDto> traces) {
    if (!properties.enabled() || traces == null || traces.isEmpty()) {
      return List.of();
    }
    String profileId = store.getOrCreateProfile(profileName).id();
    Instant receiptTime = Instant.now();
    Path repoRoot = Path.of("").toAbsolutePath();
    Path userHome = Path.of(System.getProperty("user.home", "")).toAbsolutePath();

    // 1. Redact and resolve times, deduping identical events inside the batch by content id.
    Map<String, TraceEvent> byId = new LinkedHashMap<>();
    int fromReceiptClock = 0;
    int redactionHits = 0;
    for (TraceEventDto dto : traces) {
      TraceEvent event = TraceEvent.from(
        profileId, sessionId, dto, properties.maxOutputChars(), repoRoot, userHome, receiptTime);
      byId.putIfAbsent(event.id(), event);
      fromReceiptClock += event.occurredAtFromReceipt() ? 1 : 0;
      redactionHits += event.redactionHits();
    }
    List<TraceEvent> deduped = List.copyOf(byId.values());

    // 2. Reject noise. The lookup gives the filter the active outcome for a signature without
    //    handing it a store it has no business owning.
    TraceRelevanceFilter.Result filtered =
      relevanceFilter.filter(deduped, signature -> activeOutcome(profileId, signature));
    if (filtered.kept().isEmpty()) {
      logSummary(traces.size(), deduped.size(), filtered, 0, 0, redactionHits, fromReceiptClock);
      return List.of();
    }

    // 3. Persist survivors as raw evidence. INSERT OR IGNORE over a content-addressed id, so
    //    re-shipping a spool inserts nothing.
    store.insertMessages(profileId, sessionId, filtered.kept().stream()
      .map(event -> new Message(null, sessionId, TRACE_ROLE,
        TraceMemoryFactory.rawMessageContent(event), event.occurredAt()))
      .toList());

    // 4. Deterministic outcome events, with their graph fragment and code links.
    List<Memory> stored = new ArrayList<>();
    Set<String> signatures = new LinkedHashSet<>();
    for (TraceEvent event : filtered.kept()) {
      signatures.add(event.signature());
      List<String> symbolIds = codeLinker.link(profileId, event);
      Memory memory = TraceMemoryFactory.outcome(event, symbolIds);
      GraphFragment graph = graphBuilder.build(event);
      MemoryStore.StoreOutcome outcome = store.store(profileId, memory, graph);
      if (outcome.inserted()) {
        stored.add(outcome.stored());
      }
    }

    // 5. One model pass over the batch for reusable recipes. Additive: a failure here loses the
    //    recipes, never the events already stored above.
    TraceRecipeExtractor.Result recipes = recipeExtractor.extract(filtered.kept(), knownSignatures(
      profileId, signatures));
    for (TraceRecipe recipe : recipes.recipes()) {
      Memory memory = TraceMemoryFactory.recipe(
        recipe.statement(),
        CommandSignature.of("Bash", recipe.command()),
        filtered.kept().getLast().occurredAt(),
        List.of());
      MemoryStore.StoreOutcome outcome = store.store(profileId, memory, GraphFragment.empty());
      if (outcome.inserted()) {
        stored.add(outcome.stored());
      }
    }

    logSummary(traces.size(), deduped.size(), filtered, stored.size(), recipes.dropped(),
      redactionHits, fromReceiptClock);
    return List.copyOf(stored);
  }

  private Optional<Memory> activeOutcome(String profileId, String signature) {
    List<Memory> active = store.findActiveByTopicKey(
      profileId, MemoryType.EVENT, TraceMemoryFactory.OUTCOME_KEY_PREFIX + signature);
    return active.isEmpty() ? Optional.empty() : Optional.of(active.getFirst());
  }

  /** Signatures this profile already records an outcome for; feeds the recipe cost guard. */
  private Set<String> knownSignatures(String profileId, Set<String> candidates) {
    Set<String> known = new LinkedHashSet<>();
    for (String signature : candidates) {
      if (activeOutcome(profileId, signature).isPresent()) {
        known.add(signature);
      }
    }
    return known;
  }

  /**
   * Per-stage counts, on-machine only. Redaction is reported as a <em>hit count</em>, never as
   * content — logging what was redacted would defeat redacting it.
   */
  private void logSummary(int received, int deduped, TraceRelevanceFilter.Result filtered,
                          int storedCount, int recipesDropped, int redactionHits,
                          int fromReceiptClock) {
    log.info("trace ingest: received={} deduped={} kept={} dropped={} stored={} "
        + "recipesDropped={} redactionHits={} receiptClockTimestamps={}",
      received, deduped, filtered.kept().size(), filtered.droppedByRule(), storedCount,
      recipesDropped, redactionHits, fromReceiptClock);
  }
}
