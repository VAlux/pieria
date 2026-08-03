package dev.alvo.pieria.ingestion;

import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.VerifyMode;
import dev.alvo.pieria.ingestion.model.Chunk;
import dev.alvo.pieria.ingestion.model.Classification;
import dev.alvo.pieria.ingestion.model.UnifiedCandidate;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.ingestion.model.VerificationResult;
import dev.alvo.pieria.ingestion.model.VerificationVerdict;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;
import dev.alvo.pieria.model.usage.InferenceUsageAccumulator;
import dev.alvo.pieria.model.usage.InferenceUsageSink;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.tools.Hash;
import dev.alvo.pieria.tools.StringKit;
import dev.alvo.pieria.tools.Timed;
import dev.alvo.pieria.tools.Tokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Write path: normalize the transcript, store raw messages, chunk, run parallel unified
 * extraction (one model call per chunk emits candidates <em>with</em> their classification),
 * verify suspect candidates against their source chunk (grounded candidates skip the model
 * verifier per {@link VerifyMode}), then store with supersession and enqueue vectorization.
 * Returns to the caller before vectorization completes (async worker).
 * Explicit single-memory writes ({@link #remember}) bypass the model entirely.
 *
 * <h2>Chunk-extraction ledger</h2>
 * The harness transcript hooks re-post the <em>entire</em> conversation at the end of every turn, so
 * a naive ingest re-extracts every chunk from message 0 each time — waste that grows with session
 * length. {@link Chunker#chunk} fills greedily from index 0, so appending messages never moves an
 * earlier chunk boundary: every closed chunk is byte-identical across re-ingests. Each chunk's
 * pipeline-relevant inputs are therefore hashed and recorded in the shared {@code ingest_ledger}
 * (scope {@value #CHUNK_LEDGER_SCOPE}) <em>after</em> its memories are durably stored, and a chunk
 * whose hash is already ledgered skips extraction, verification, and graph extraction entirely.
 * Same discipline as {@code ContentIngestor}'s document-level ledger: never claim work that did not
 * finish, so an interrupted run resumes exactly where it stopped.
 */
@Service
public class IngestionService {

  private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

  /** {@code ingest_ledger.scope} for conversation chunks, alongside the onboarding source types. */
  static final String CHUNK_LEDGER_SCOPE = "chunk";

  /**
   * Salt for the chunk ledger hashes; bump on prompt/pipeline changes that should force
   * re-extraction of already-processed chunks (same convention as {@code ContentIngestor}).
   */
  static final String CHUNK_PIPELINE_VERSION = "v1";

  private final MemoryStore store;
  private final ModelGateway modelGateway;
  private final TranscriptNormalizer normalizer;
  private final Chunker chunker;
  private final EffectiveConfigResolver configResolver;

  /**
   * Merged extraction output: the raw candidate count (for logging) and the de-duplicated list.
   */
  private record Extraction(int rawCount, List<UnifiedCandidate> merged) {
  }

  /**
   * Combined verify+store output: per-verdict counts (with {@code autoPassed} counting candidates
   * the grounding filter cleared without a model call) plus the persisted memories and their
   * supersession / vectorization-enqueue / graph tallies.
   */
  private record VerifyStoreResult(List<Memory> stored, int passed, int autoPassed, int corrected,
                                   int dropped, int superseded, int enqueued,
                                   int graphExtracted, int graphSkipped, int graphFailed,
                                   int graphDeferred, long verificationWaitMs, long graphCallMs,
                                   long reclassificationMs, long sqliteStoreMs) {
  }

  /** Outcome of persisting one verified candidate (classify + graph + store). */
  private record StoredOne(Memory stored, boolean superseded, boolean enqueued, GraphOutcome graph) {
  }

  /** Whether graph extraction ran, was skipped (tasks), or failed (degraded, memory still stored). */
  private enum GraphOutcome {EXTRACTED, SKIPPED, FAILED, DEFERRED}

  /**
   * Result of one extraction model call, keeping chunk metadata for detailed logging.
   */
  private record ExtractionPassResult(int chunkIndex, List<UnifiedCandidate> candidates, long millis) {
  }

  public IngestionService(MemoryStore store,
                          ModelGateway modelGateway,
                          TranscriptNormalizer normalizer,
                          Chunker chunker,
                          EffectiveConfigResolver configResolver) {
    this.store = store;
    this.modelGateway = modelGateway;
    this.normalizer = normalizer;
    this.chunker = chunker;
    this.configResolver = configResolver;
  }

  /**
   * Ingest a conversation through the full pipeline: normalize → store raw messages → chunk →
   * extract → verify → classify and store. Idempotent — re-ingesting the same transcript yields
   * the same content-addressed messages and memories (insert-or-ignore). Each stage is timed and
   * reported in the latency log line; vectorization is enqueued, not awaited.
   */
  public List<Memory> ingest(String profileName, String sessionId, List<Message> messages) {
    return ingest(profileName, sessionId, messages, null, IngestProgressListener.noop());
  }

  /**
   * As {@link #ingest(String, String, List)}, reporting coarse per-phase progress through
   * {@code progress} so a long-running ingest can be observed while it runs.
   */
  public List<Memory> ingest(String profileName, String sessionId, List<Message> messages,
                             IngestProgressListener progress) {
    return ingest(profileName, sessionId, messages, null, progress);
  }

  /**
   * As {@link #ingest(String, String, List)}, overriding the number of extraction samples per chunk
   * for this one call (null ⇒ the profile's configured default). Used by bulk seeds that want
   * saturated extraction without raising the cost of every ingest.
   */
  public List<Memory> ingest(String profileName, String sessionId, List<Message> messages,
                             Integer extractionSamplesOverride) {
    return ingest(profileName, sessionId, messages, extractionSamplesOverride, IngestProgressListener.noop());
  }

  /**
   * As {@link #ingest(String, String, List, IngestProgressListener)}, additionally overriding the
   * per-chunk extraction sample count ({@code extractionSamplesOverride}; null ⇒ profile default).
   */
  public List<Memory> ingest(String profileName, String sessionId, List<Message> messages,
                             Integer extractionSamplesOverride, IngestProgressListener progress) {
    return ingest(profileName, sessionId, messages, extractionSamplesOverride,
      ChunkLedgerMode.ENABLED, progress);
  }

  /**
   * As {@link #ingest(String, String, List, Integer, IngestProgressListener)}, with explicit control
   * over the chunk-extraction ledger. A routine per-turn transcript capture passes
   * {@link ChunkLedgerMode#DEFER_TRAILING} so the still-growing trailing chunk is left for the next
   * boundary or the final flush.
   */
  public List<Memory> ingest(String profileName, String sessionId, List<Message> messages,
                             Integer extractionSamplesOverride, ChunkLedgerMode chunkLedgerMode,
                             IngestProgressListener progress) {
    return ingestDetailed(profileName, sessionId, messages, extractionSamplesOverride,
      GraphMode.SYNCHRONOUS, chunkLedgerMode, progress).memories();
  }

  /** Internal detailed entry point used by bulk onboarding to defer graph enrichment. */
  public IngestionResult ingestDetailed(String profileName, String sessionId, List<Message> messages,
                                        Integer extractionSamplesOverride, GraphMode graphMode,
                                        IngestProgressListener progress) {
    return ingestDetailed(profileName, sessionId, messages, extractionSamplesOverride, graphMode,
      ChunkLedgerMode.ENABLED, progress);
  }

  /**
   * Internal detailed entry point: as above, additionally selecting how this ingest uses the
   * chunk-extraction ledger. Bulk onboarding passes {@link ChunkLedgerMode#DISABLED} — it already
   * dedupes per document, and its per-batch chunk indices would collide inside its one fixed
   * session id.
   */
  public IngestionResult ingestDetailed(String profileName, String sessionId, List<Message> messages,
                                        Integer extractionSamplesOverride, GraphMode graphMode,
                                        ChunkLedgerMode chunkLedgerMode,
                                        IngestProgressListener progress) {
    long totalStart = System.nanoTime();
    int inputMessages = messages == null ? 0 : messages.size();
    log.info("ingest start profile={} session={} inputMessages={}", profileName, sessionId, inputMessages);

    Profile profile = store.getOrCreateProfile(profileName);
    log.debug("ingest resolved profile name={} id={}", profileName, profile.id());

    Timed<List<Message>> normalize = Timed.measure(() -> normalizeMessages(messages, sessionId));
    List<Message> normalized = normalize.value();
    log.debug("ingest normalized profile={} session={} inputMessages={} normalizedMessages={} normalizeMs={}",
      profileName, sessionId, inputMessages, normalized.size(), normalize.millis());

    // Store raw messages first so ingest is inspectable even when nothing is extracted.
    Timed<Void> messageStore =
      Timed.measure(() -> store.insertMessages(profile.id(), sessionId, normalized));
    log.debug("ingest stored raw messages profile={} session={} messages={} messageStoreMs={}",
      profileName, sessionId, normalized.size(), messageStore.millis());
    if (normalized.isEmpty()) {
      log.info("ingest profile={} session={} messages=0 normalizeMs={} messageStoreMs={} totalMs={} — nothing to extract",
        profileName, sessionId, normalize.millis(), messageStore.millis(), Timed.elapsedMillis(totalStart));
      return new IngestionResult(List.of(), 0);
    }

    // Per-profile effective tuning: global properties overlaid with any pushed overrides.
    PieriaProperties.Ingestion tuning = configResolver.resolve(profile.id()).ingestion();

    int extractionSamples = Math.max(1,
      extractionSamplesOverride != null ? extractionSamplesOverride : tuning.extractionSamples());
    Timed<List<Chunk>> chunk = Timed.measure(() -> chunker.chunk(normalized, tuning));
    List<Chunk> chunks = chunk.value();
    log.info(
      "ingest chunked profile={} session={} chunks={} extractionSamples={} verifyMode={} maxExtractionConcurrency={} chunkMs={}",
      profileName, sessionId, chunks.size(), extractionSamples, tuning.verifyMode(),
      Math.max(1, tuning.maxExtractionConcurrency()), chunk.millis());

    // Chunk-extraction ledger: drop the chunks an earlier ingest of this session already processed
    // (the transcript hooks re-post the whole conversation every turn), and optionally hold back the
    // still-growing trailing chunk. The full chunk list is still handed to verifyStore below — it
    // walks every chunk in order to attribute source tokens without double-counting the overlap.
    ChunkPlan plan = planChunks(profile.id(), sessionId, chunks, extractionSamples, tuning,
      chunkLedgerMode == null ? ChunkLedgerMode.ENABLED : chunkLedgerMode);
    if (plan.skipped() > 0 || plan.deferred() > 0) {
      log.info("ingest chunk ledger profile={} session={} pending={} skipped={} deferred={}",
        profileName, sessionId, plan.pending().size(), plan.skipped(), plan.deferred());
    }
    if (plan.pending().isEmpty()) {
      log.info("ingest profile={} session={} chunks={} skipped={} deferred={} totalMs={} — nothing left to"
          + " extract, no model calls made", profileName, sessionId, chunks.size(), plan.skipped(),
        plan.deferred(), Timed.elapsedMillis(totalStart));
      return new IngestionResult(List.of(), 0);
    }

    // One accumulator for this whole ingest: every model call (extract/verify/classify/graph),
    // whether on this thread or a virtual-thread worker, reports its real provider token usage here.
    InferenceUsageAccumulator inferenceUsage = new InferenceUsageAccumulator();

    Timed<Extraction> extract = Timed.measure(
      () -> extractCandidates(plan.pending(), extractionSamples,
        Math.max(1, tuning.maxExtractionConcurrency()), inferenceUsage, progress));
    Extraction extraction = extract.value();
    log.info("ingest extracted profile={} session={} rawCandidates={} mergedCandidates={} duplicatesOrBlank={} extractionMs={}",
      profileName, sessionId, extraction.rawCount(), extraction.merged().size(),
      Math.max(0, extraction.rawCount() - extraction.merged().size()), extract.millis());
    if (extraction.merged().isEmpty()) {
      log.warn("ingest extraction produced no candidates profile={} session={} — the model returned no parseable"
        + " memories for any chunk (check for unparseable-output warnings above); nothing will be stored",
        profileName, sessionId);
    }

    // Verify + store as one stage: each candidate is persisted the moment its verification passes,
    // so an interrupted run keeps every memory it finished (the task is in-memory and dies on
    // daemon restart — incremental storage is what makes partial progress durable). Candidates the
    // grounding filter clears skip the model verifier entirely (per verifyMode); the suspect
    // verification calls run bounded-parallel; the store write stays single-threaded.
    Timed<VerifyStoreResult> verifyStore = Timed.measure(() -> verifyStore(
      extraction.merged(), chunks, plan, profile.id(), sessionId, tuning.verifyMode(),
      Math.max(1, tuning.maxExtractionConcurrency()), inferenceUsage,
      graphMode == null ? GraphMode.SYNCHRONOUS : graphMode, progress));
    VerifyStoreResult result = verifyStore.value();
    log.info("ingest verified profile={} session={} passed={} autoPassed={} corrected={} dropped={} stored={} verifyStoreMs={}",
      profileName, sessionId, result.passed(), result.autoPassed(), result.corrected(),
      result.dropped(), result.stored().size(), verifyStore.millis());
    log.info("ingest verifyStore timings profile={} session={} verificationWaitMs={} graphCallMs={}"
        + " reclassificationMs={} sqliteStoreMs={} verifyStoreMs={}",
      profileName, sessionId, result.verificationWaitMs(), result.graphCallMs(),
      result.reclassificationMs(), result.sqliteStoreMs(), verifyStore.millis());
    if (!extraction.merged().isEmpty() && result.stored().isEmpty()) {
      log.warn("ingest verification dropped every candidate profile={} session={} merged={} — see per-candidate"
        + " drop reasons above; nothing was stored", profileName, sessionId, extraction.merged().size());
    }

    log.info("""
        ingest \
        profile={} \
        session={} \
        messages={} \
        chunks={} \
        extracted={} \
        merged={} \
        dropped={} \
        stored={} \
        superseded={} \
        vectorJobs={} \
        graphExtracted={} \
        graphSkipped={} \
        graphFailed={} \
        graphDeferred={}""",
      profileName,
      sessionId,
      normalized.size(),
      chunks.size(),
      extraction.rawCount(),
      extraction.merged().size(),
      result.dropped(),
      result.stored().size(),
      result.superseded(),
      result.enqueued(),
      result.graphExtracted(),
      result.graphSkipped(),
      result.graphFailed(),
      result.graphDeferred());

    log.info("""
        ingest latency \
        profile={} \
        session={} \
        normalizeMs={} \
        messageStoreMs={} \
        chunkMs={} \
        extractionMs={} \
        verifyStoreMs={} \
        totalMs={}""",
      profileName,
      sessionId,
      normalize.millis(),
      messageStore.millis(),
      chunk.millis(),
      extract.millis(),
      verifyStore.millis(),
      Timed.elapsedMillis(totalStart));

    recordUsage(profile.id(), normalized, result.stored());
    recordInferenceUsage(profile.id(), inferenceUsage);

    return new IngestionResult(result.stored(), result.graphDeferred());
  }

  /** Normalize and persist a source's raw messages before its first extraction batch starts. */
  public int preStageMessages(String profileName, String sessionId, List<Message> messages) {
    Profile profile = store.getOrCreateProfile(profileName);
    List<Message> normalized = normalizeMessages(messages, sessionId);
    store.insertMessages(profile.id(), sessionId, normalized);
    return normalized.size();
  }

  /**
   * Accumulate this ingest's real provider token usage into the profile's lifetime inference-spend
   * counters, per model tier. Accounting-only and best-effort — a failure here must never break
   * ingest, and an operation that spent nothing records nothing.
   */
  private void recordInferenceUsage(String profileId, InferenceUsageAccumulator usage) {
    try {
      store.recordInferenceUsage(profileId, usage.snapshot());
    } catch (RuntimeException e) {
      log.warn("recording ingest inference usage failed ({}); continuing", e.toString());
    }
  }

  /**
   * Wrap {@code task} so it binds {@code usage} to whatever (virtual) thread runs it before calling
   * the gateway, then restores the prior binding. Required because the extraction/verify executors
   * are virtual-thread-per-task and do not inherit thread-locals, so the binding must be established
   * on the worker thread itself rather than propagated from the submitter.
   */
  private static <T> Callable<T> tracked(InferenceUsageAccumulator usage, Callable<T> task) {
    return () -> {
      try (InferenceUsageSink.Binding ignored = InferenceUsageSink.bind(usage)) {
        return task.call();
      }
    };
  }

  /**
   * Accumulate this ingest into the profile's lifetime savings counters: the raw-message tokens fed
   * in versus the distilled-memory tokens produced (the compression story). Accounting-only and
   * best-effort — a failure here must never break ingest, and an ingest that stored nothing is not
   * recorded.
   */
  private void recordUsage(String profileId, List<Message> normalized, List<Memory> stored) {
    if (stored.isEmpty()) {
      return;
    }
    try {
      long ingestedTokens = normalized.stream().mapToLong(m -> Tokens.estimate(m.content())).sum();
      long storedTokens = stored.stream().mapToLong(m -> Tokens.estimate(m.content())).sum();
      store.recordIngestUsage(profileId, ingestedTokens, storedTokens);
    } catch (RuntimeException e) {
      log.warn("recording ingest usage failed ({}); continuing", e.toString());
    }
  }

  /**
   * Stamp the session id onto messages that lack one, then validate and normalize the transcript.
   */
  private List<Message> normalizeMessages(List<Message> messages, String sessionId) {
    List<Message> withSession = new ArrayList<>(messages == null ? 0 : messages.size());
    if (messages != null) {
      for (Message m : messages) {
        withSession.add(m.sessionId() == null
          ? new Message(m.id(), sessionId, m.role(), m.content(), m.createdAt())
          : m);
      }
    }
    return normalizer.normalize(withSession, Instant.now());
  }

  /**
   * Run unified extraction over the chunks and merge the candidates, retaining the raw count for
   * logging.
   */
  private Extraction extractCandidates(List<Chunk> chunks, int extractionSamples,
                                       int maxExtractionConcurrency,
                                       InferenceUsageAccumulator usage, IngestProgressListener progress) {
    List<UnifiedCandidate> extracted =
      runExtraction(chunks, extractionSamples, maxExtractionConcurrency, usage, progress);
    List<UnifiedCandidate> merged = mergeCandidates(extracted);
    return new Extraction(extracted.size(), merged);
  }

  /**
   * The chunk-level work this ingest will actually do, after the ledger has been consulted.
   *
   * @param pending          chunks that still need the model pipeline, in chunk order
   * @param hashByChunkIndex ledger hash for every chunk (pending or not), by {@code Chunk.index()}
   * @param skipped          chunks a previous ingest already processed
   * @param deferred         chunks held back because they can still grow (trailing chunk)
   * @param ledgerEnabled    whether results should be written back to the ledger at all
   */
  private record ChunkPlan(List<Chunk> pending, Map<Integer, String> hashByChunkIndex,
                           int skipped, int deferred, boolean ledgerEnabled) {
  }

  /**
   * Decide which chunks need the model. With the ledger off every chunk is pending, exactly as
   * before this optimization existed.
   *
   * <p>Only the trailing chunk can still grow — {@link Chunker#chunk} fills greedily from index 0,
   * so appending messages never moves an earlier boundary. That makes closed chunks byte-identical
   * across re-ingests (they hit the ledger) while the trailing one changes every turn and would miss
   * it every time, which is why {@link ChunkLedgerMode#DEFER_TRAILING} holds it back.
   */
  private ChunkPlan planChunks(String profileId, String sessionId, List<Chunk> chunks,
                               int extractionSamples, PieriaProperties.Ingestion tuning,
                               ChunkLedgerMode mode) {
    if (mode == ChunkLedgerMode.DISABLED || !tuning.chunkLedgerEnabled() || chunks.isEmpty()) {
      return new ChunkPlan(chunks, Map.of(), 0, 0, false);
    }

    // Fail open: a backend without ledger support (or a transient read failure) costs the
    // optimization, never the ingest — every chunk simply stays pending, as before the ledger.
    Map<String, String> ledger;
    try {
      ledger = store.ingestLedger(profileId, CHUNK_LEDGER_SCOPE);
    } catch (RuntimeException e) {
      // Includes the UnsupportedOperationException a store without ledger support throws.
      log.debug("chunk ledger unavailable ({}); extracting every chunk", e.toString());
      return new ChunkPlan(chunks, Map.of(), 0, 0, false);
    }

    Map<Integer, String> hashByChunkIndex = new LinkedHashMap<>();
    List<Chunk> pending = new ArrayList<>();
    int lastIndex = chunks.size() - 1;
    int skipped = 0;
    int deferred = 0;

    for (int i = 0; i < chunks.size(); i++) {
      Chunk chunk = chunks.get(i);
      String hash = chunkHash(chunk, extractionSamples, tuning);
      hashByChunkIndex.put(chunk.index(), hash);
      if (hash.equals(ledger.get(chunkLedgerKey(sessionId, chunk.index())))) {
        skipped++;
      } else if (i == lastIndex && mode == ChunkLedgerMode.DEFER_TRAILING) {
        deferred++;
      } else {
        pending.add(chunk);
      }
    }
    return new ChunkPlan(List.copyOf(pending), Map.copyOf(hashByChunkIndex), skipped, deferred, true);
  }

  /**
   * Ledger item key for one chunk. Scoped by session so two sessions that happen to contain
   * identical text each get their own memories — {@code ContentId.forMemory} includes the session
   * id, so deduping across sessions here would silently drop the second session's rows.
   */
  static String chunkLedgerKey(String sessionId, int chunkIndex) {
    return StringKit.nullToEmpty(sessionId) + "#" + chunkIndex;
  }

  /**
   * The ledger hash of one chunk: everything that determines what the pipeline would produce for it.
   * The three tuning knobs all shape the extraction prompt (candidate cap, interrogative-query
   * count, inline graph), so changing any of them must re-extract rather than silently reuse a
   * result produced under the old instructions. {@code verifyMode} is deliberately absent: the
   * ledger records that a chunk's memories are stored, not the level they were verified at.
   */
  static String chunkHash(Chunk chunk, int extractionSamples, PieriaProperties.Ingestion tuning) {
    return Hash.hash128(
      CHUNK_PIPELINE_VERSION,
      String.valueOf(extractionSamples),
      String.valueOf(tuning.maxExtractedCandidatesPerChunk()),
      String.valueOf(tuning.interrogativeQueriesPerMemory()),
      String.valueOf(tuning.graphFromExtraction()),
      chunk.transcript() == null ? "" : chunk.transcript());
  }

  /**
   * Checkpoint one chunk as fully processed. Called only after that chunk's memories are durably
   * stored — the ledger must never claim work that did not finish, or a crashed run would silently
   * lose those memories.
   */
  private void recordChunkProcessed(ChunkPlan plan, String profileId, String sessionId, int chunkIndex) {
    if (!plan.ledgerEnabled()) {
      return;
    }
    String hash = plan.hashByChunkIndex().get(chunkIndex);
    if (hash == null) {
      return;
    }
    try {
      store.recordIngestLedger(profileId, CHUNK_LEDGER_SCOPE,
        Map.of(chunkLedgerKey(sessionId, chunkIndex), hash));
    } catch (RuntimeException e) {
      // A ledger write failure only costs a re-extraction next time; never fail the ingest for it.
      log.warn("recording chunk ledger for chunk={} failed ({}); it will be re-extracted", chunkIndex, e.toString());
    }
  }

  /** One verified survivor ready to store: resolved content plus its classification. */
  private record Survivor(String content, Classification classification, GraphFragment extractionGraph) {
  }

  /** A chunk's candidates split by the grounding pre-filter (per {@link VerifyMode}). */
  private record ChunkPartition(List<UnifiedCandidate> autoPassed, List<UnifiedCandidate> suspects) {
  }

  /**
   * Verify each candidate against its source chunk, then store every survivor <em>immediately</em> —
   * so each memory is durably persisted the moment its verification passes, and an interrupted run
   * keeps everything it finished.
   *
   * <p>Candidates arrive already classified (unified extraction), so there is no classify stage:
   * only a {@code CORRECT} verdict re-classifies, because the corrected content invalidates the
   * original enrichment. Per {@code verifyMode}, candidates that pass the deterministic
   * {@link GroundingFilter} skip the model verifier entirely; the remaining suspects of each chunk
   * are verified in ONE batched call (the transcript is sent once, not re-sent per candidate).
   * Suspect verification calls are independent and read-only, so they run bounded-parallel on
   * virtual threads (gated by {@code maxConcurrency}, like extraction). Results are folded back in
   * submission order on this single thread, which also performs the graph extraction + store for
   * each survivor — keeping the daemon's single-writer invariant and deterministic supersession
   * ordering.
   *
   * <p>{@code chunks} is the ingest's <em>full</em> chunk list even when the ledger made only some
   * of them pending: the source-token attribution below walks every chunk in order so a message
   * shared by two overlapping chunks is counted once. {@code plan} carries which of them are pending
   * and their ledger hashes; each chunk is checkpointed as it finishes.
   */
  private VerifyStoreResult verifyStore(List<UnifiedCandidate> merged, List<Chunk> chunks,
                                        ChunkPlan plan,
                                        String profileId, String sessionId, VerifyMode verifyMode,
                                        int maxConcurrency,
                                        InferenceUsageAccumulator usage, GraphMode graphMode,
                                        IngestProgressListener progress) {
    int total = merged.size();
    if (total == 0) {
      // Extraction ran and produced nothing to store, so every pending chunk is finished. Ledger
      // them here or they would be re-extracted on every future ingest of this session.
      for (Chunk pending : plan.pending()) {
        recordChunkProcessed(plan, profileId, sessionId, pending.index());
      }
      return new VerifyStoreResult(List.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0);
    }

    Map<Integer, String> transcriptByChunk = new HashMap<>();
    for (Chunk c : chunks) {
      transcriptByChunk.put(c.index(), c.transcript());
    }

    // Raw source tokens per chunk: what re-reading this chunk's turns would cost, counting each
    // message exactly once. Chunks deliberately overlap (pieria.ingestion.chunk-overlap-messages),
    // so a message is attributed to the first chunk that contains it; summed over all chunks this
    // equals the ingest's total message tokens, so per-memory slices never over-attribute.
    Map<Integer, Long> sourceTokensByChunk = new HashMap<>();
    int attributedThrough = -1; // highest absolute message index already attributed
    for (Chunk c : chunks) {
      long newTokens = 0;
      for (int i = 0; i < c.messages().size(); i++) {
        if (c.firstMessageIndex() + i > attributedThrough) {
          newTokens += Tokens.estimate(c.messages().get(i).content());
        }
      }
      attributedThrough = Math.max(attributedThrough, c.lastMessageIndex());
      sourceTokensByChunk.put(c.index(), newTokens);
    }

    // Group candidates by source chunk (first-seen order), then split each group into auto-passed
    // (grounded) and suspects that need the model verifier.
    LinkedHashMap<Integer, List<UnifiedCandidate>> byChunk = new LinkedHashMap<>();
    for (UnifiedCandidate candidate : merged) {
      byChunk.computeIfAbsent(candidate.chunkIndex(), k -> new ArrayList<>()).add(candidate);
    }
    LinkedHashMap<Integer, ChunkPartition> partitions = new LinkedHashMap<>();
    for (var entry : byChunk.entrySet()) {
      partitions.put(entry.getKey(),
        partition(entry.getValue(), transcriptByChunk.getOrDefault(entry.getKey(), ""), verifyMode));
    }

    int passed = 0;
    int autoPassed = 0;
    int corrected = 0;
    int dropped = 0;
    int superseded = 0;
    int enqueued = 0;
    int graphExtracted = 0;
    int graphSkipped = 0;
    int graphFailed = 0;
    int graphDeferred = 0;
    long verificationWaitNanos = 0;
    long graphCallNanos = 0;
    long reclassificationNanos = 0;
    long sqliteStoreNanos = 0;
    List<Memory> stored = new ArrayList<>();

    Semaphore gate = new Semaphore(maxConcurrency);
    // Bind on this fold thread so the synchronous classify/graph calls below record their usage;
    // the verify calls run on worker threads and bind via tracked(...).
    try (InferenceUsageSink.Binding ignored = InferenceUsageSink.bind(usage);
         ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
      // One batched verify call per chunk over its suspects only, bounded-parallel across chunks.
      List<Integer> chunkOrder = new ArrayList<>(partitions.keySet());
      Map<Integer, Future<List<VerificationResult>>> verifyFutures = new LinkedHashMap<>();
      for (Integer chunkIndex : chunkOrder) {
        List<UnifiedCandidate> suspects = partitions.get(chunkIndex).suspects();
        if (suspects.isEmpty()) {
          continue;
        }
        List<String> contents = suspects.stream().map(UnifiedCandidate::content).toList();
        String transcript = transcriptByChunk.getOrDefault(chunkIndex, "");
        verifyFutures.put(chunkIndex,
          exec.submit(tracked(usage, () -> bounded(gate, () -> modelGateway.verifyAll(contents, transcript)))));
      }

      // Announce the phase up front (see extraction) and fold per chunk in submission order.
      progress.onPhase("verify", 0, total);
      int done = 0;
      for (Integer chunkIndex : chunkOrder) {
        ChunkPartition part = partitions.get(chunkIndex);
        List<Survivor> survivors = new ArrayList<>();

        for (UnifiedCandidate candidate : part.autoPassed()) {
          autoPassed++;
          survivors.add(new Survivor(candidate.content(), candidate.classification(), candidate.graph()));
          log.debug("ingest verification chunk={} verdict=AUTO_PASS (grounded)", chunkIndex);
          progress.onPhase("verify", ++done, total);
        }

        List<UnifiedCandidate> suspects = part.suspects();
        if (!suspects.isEmpty()) {
          long verificationWaitStart = System.nanoTime();
          List<VerificationResult> verdicts = awaitVerification(verifyFutures.get(chunkIndex));
          verificationWaitNanos += System.nanoTime() - verificationWaitStart;
          for (int j = 0; j < suspects.size(); j++) {
            UnifiedCandidate candidate = suspects.get(j);
            VerificationResult result = j < verdicts.size() ? verdicts.get(j)
              : new VerificationResult(VerificationVerdict.DROP, "", "no verdict returned");

            switch (result.verdict()) {
              case DROP -> {
                dropped++;
                // Drops are the usual reason a profile stays empty, so log them at info with the
                // model's reason; pass/correct stay at debug to keep healthy runs quiet.
                log.info("ingest verification dropped chunk={} reason={} content=\"{}\"",
                  chunkIndex, result.reason(), abbreviate(candidate.content()));
              }
              case PASS -> {
                passed++;
                survivors.add(new Survivor(candidate.content(), candidate.classification(), candidate.graph()));
                log.debug("ingest verification chunk={} verdict=PASS reason={}", chunkIndex, result.reason());
              }
              case CORRECT -> {
                corrected++;
                String content = result.content() != null && !result.content().isBlank()
                  ? result.content()
                  : candidate.content();
                long reclassifyStart = System.nanoTime();
                Classification classification = reclassify(content, candidate.classification());
                reclassificationNanos += System.nanoTime() - reclassifyStart;
                // A corrected statement invalidates graph fragments extracted for the original.
                survivors.add(new Survivor(content, classification, GraphFragment.empty()));
                log.debug("ingest verification chunk={} verdict=CORRECT reason={}", chunkIndex, result.reason());
              }
            }
            progress.onPhase("verify", ++done, total);
          }
        }

        // Batch graph-extract the non-task survivors (one call), then store each. Storing per
        // chunk keeps progress durable: a finished chunk's memories survive an interrupted run.
        if (!survivors.isEmpty()) {
          // Split this chunk's source across the memories it produced, so the slices sum to the
          // chunk (ceil rounding may add at most one token per memory).
          long chunkSourceTokens = sourceTokensByChunk.getOrDefault(chunkIndex, 0L);
          long perMemorySourceTokens =
            (chunkSourceTokens + survivors.size() - 1) / survivors.size();

          // Tasks are excluded from the graph (as from the vector index); the rest are graph-extracted
          // in one batched, degradable call.
          List<String> graphContents = new ArrayList<>();
          for (Survivor survivor : survivors) {
            if (survivor.classification().type() != MemoryType.TASK
              && survivor.extractionGraph().isEmpty()) {
              graphContents.add(survivor.content());
            }
          }
          List<GraphFragment> graphs;
          boolean graphBatchFailed = false;
          if (graphMode == GraphMode.DEFERRED) {
            graphs = List.of();
          } else {
            long graphCallStart = System.nanoTime();
            try {
              graphs = modelGateway.extractGraphAll(graphContents);
            } catch (RuntimeException e) {
              log.warn("graph extraction batch failed; storing this chunk's memories without graph: {}", e.toString());
              graphs = List.of();
              graphBatchFailed = true;
            } finally {
              graphCallNanos += System.nanoTime() - graphCallStart;
            }
          }

          int g = 0;
          for (int k = 0; k < survivors.size(); k++) {
            Survivor survivor = survivors.get(k);
            GraphFragment graph = GraphFragment.empty();
            GraphOutcome graphOutcome;
            if (survivor.classification().type() == MemoryType.TASK) {
              graphOutcome = GraphOutcome.SKIPPED;
            } else if (!survivor.extractionGraph().isEmpty()) {
              graph = survivor.extractionGraph();
              graphOutcome = GraphOutcome.EXTRACTED;
            } else if (graphMode == GraphMode.DEFERRED) {
              graphOutcome = GraphOutcome.DEFERRED;
            } else if (graphBatchFailed) {
              graphOutcome = GraphOutcome.FAILED;
              g++;
            } else {
              graph = g < graphs.size() ? graphs.get(g) : GraphFragment.empty();
              graphOutcome = GraphOutcome.EXTRACTED;
              g++;
            }

            long storeStart = System.nanoTime();
            StoredOne s = storeMemory(profileId, sessionId, survivor.content(), survivor.classification(),
              graph, graphOutcome, perMemorySourceTokens, k + 1, survivors.size());
            sqliteStoreNanos += System.nanoTime() - storeStart;
            stored.add(s.stored());
            if (s.superseded()) {
              superseded++;
            }
            if (s.enqueued()) {
              enqueued++;
            }
            switch (s.graph()) {
              case EXTRACTED -> graphExtracted++;
              case SKIPPED -> graphSkipped++;
              case FAILED -> graphFailed++;
              case DEFERRED -> graphDeferred++;
            }
          }
        }

        // Checkpoint only now, after this chunk's memories are durably stored — same discipline as
        // ContentIngestor's per-batch checkpoint. An interrupted run keeps every chunk it finished
        // and re-extracts only the rest.
        recordChunkProcessed(plan, profileId, sessionId, chunkIndex);
      }

      // Pending chunks that contributed no candidate of their own are finished too: extraction ran
      // for them and mergeCandidates folded whatever they found into an earlier chunk's candidate.
      // Without this they would never be ledgered and so re-extract on every future ingest.
      for (Chunk pending : plan.pending()) {
        if (!partitions.containsKey(pending.index())) {
          recordChunkProcessed(plan, profileId, sessionId, pending.index());
        }
      }
    }

    return new VerifyStoreResult(stored, passed, autoPassed, corrected, dropped,
      superseded, enqueued, graphExtracted, graphSkipped, graphFailed, graphDeferred,
      nanosToMillis(verificationWaitNanos), nanosToMillis(graphCallNanos),
      nanosToMillis(reclassificationNanos), nanosToMillis(sqliteStoreNanos));
  }

  private static long nanosToMillis(long nanos) {
    return nanos / 1_000_000L;
  }

  /**
   * Split a chunk's candidates per {@code verifyMode}: {@code NEVER} auto-passes everything,
   * {@code ALWAYS} sends everything to the model verifier, {@code GROUNDED} auto-passes only the
   * candidates the {@link GroundingFilter} clears against the chunk transcript.
   */
  private static ChunkPartition partition(List<UnifiedCandidate> candidates, String transcript,
                                          VerifyMode verifyMode) {
    return switch (verifyMode) {
      case NEVER -> new ChunkPartition(candidates, List.of());
      case ALWAYS -> new ChunkPartition(List.of(), candidates);
      case GROUNDED -> {
        List<UnifiedCandidate> auto = new ArrayList<>();
        List<UnifiedCandidate> suspects = new ArrayList<>();
        for (UnifiedCandidate candidate : candidates) {
          if (GroundingFilter.grounded(candidate.content(), transcript)) {
            auto.add(candidate);
          } else {
            suspects.add(candidate);
          }
        }
        yield new ChunkPartition(auto, suspects);
      }
    };
  }

  /**
   * Re-classify content the verifier corrected: the original enrichment (type, topic key,
   * interrogative queries) was computed for the uncorrected statement. Degradable — on a model
   * failure the stale classification is kept (corrected content with slightly-off enrichment beats
   * losing the memory).
   */
  private Classification reclassify(String content, Classification stale) {
    try {
      return modelGateway.classify(content);
    } catch (RuntimeException e) {
      log.warn("re-classify of corrected content failed ({}); keeping the original classification", e.toString());
      return stale;
    }
  }

  private static List<VerificationResult> awaitVerification(Future<List<VerificationResult>> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ModelUnavailableException("verification interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        throw new ModelUnavailableException("verification interrupted", cause);
      }
      if (cause instanceof ModelUnavailableException mue) {
        throw mue;
      }
      throw new ModelUnavailableException("verification failed", cause);
    }
  }

  /**
   * Store one classified survivor with its pre-computed {@link GraphFragment} (both come from the
   * batched classify + graph passes), applying supersession and vectorization enqueue. Runs on the
   * single fold thread so the store write stays serialized. Graph extraction is additive and
   * degradable: {@code graph} is already empty when extraction was skipped (tasks) or failed, and the
   * memory is stored regardless.
   *
   * <p>{@code sourceTokens} is this memory's proportional slice of its source chunk's raw turns —
   * the re-read cost the impact panel reports against.
   */
  private StoredOne storeMemory(String profileId, String sessionId, String content,
                                Classification classification, GraphFragment graph, GraphOutcome graphOutcome,
                                long sourceTokens, int index, int total) {
    log.debug("ingest classified candidate={}/{} type={} hasTopicKey={} interrogativeQueries={} graph={}",
      index, total, classification.type(), classification.topicKey() != null,
      classification.interrogativeQueries() == null ? 0 : classification.interrogativeQueries().size(),
      graphOutcome);

    String embedText = buildEmbedText(classification.interrogativeQueries(), content);
    String payload = classification.payload() == null ? "{}" : classification.payload();
    Memory candidate = new Memory(
      null, sessionId, classification.type(), content, classification.topicKey(),
      null, false, payload, embedText, null);

    MemoryStore.StoreOutcome outcome = store.store(profileId, candidate, graph, sourceTokens);
    log.debug("ingest stored candidate={}/{} memoryId={} type={} supersededId={} vectorEnqueued={}",
      index, total, outcome.stored().id(), outcome.stored().type(), outcome.supersededId(),
      outcome.enqueuedVector());
    return new StoredOne(outcome.stored(), outcome.supersededId() != null, outcome.enqueuedVector(), graphOutcome);
  }

  /**
   * Run the unified extraction pass over all chunks with parallelism bounded by
   * {@code maxExtractionConcurrency}. Blocking model calls run on virtual threads.
   *
   * <p>When {@code extractionSamples > 1} the pass is repeated that many times per chunk: extraction
   * is stochastic, so independent samples catch overlapping-but-different subsets of a chunk's facts,
   * and their union (de-duplicated downstream by {@link #mergeCandidates}) is more complete than any
   * single pass. All samples are submitted together and share the same concurrency gate.
   */
  private List<UnifiedCandidate> runExtraction(List<Chunk> chunks, int extractionSamples,
                                               int maxExtractionConcurrency,
                                               InferenceUsageAccumulator usage, IngestProgressListener progress) {
    if (chunks.isEmpty()) {
      log.debug("ingest extraction skipped because there are no chunks");
      return List.of();
    }
    int samples = Math.max(1, extractionSamples);
    log.info("ingest extraction start chunks={} samples={} maxConcurrency={} modelCalls={}",
      chunks.size(), samples, maxExtractionConcurrency, chunks.size() * samples);

    Semaphore gate = new Semaphore(maxExtractionConcurrency);
    List<UnifiedCandidate> results = new ArrayList<>();

    try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<ExtractionPassResult>> futures = new ArrayList<>();
      for (Chunk chunk : chunks) {
        for (int sample = 0; sample < samples; sample++) {
          futures.add(exec.submit(tracked(usage,
            () -> extractWithTiming(gate, chunk, () -> modelGateway.extractUnified(chunk)))));
        }
      }
      int total = futures.size();
      int done = 0;
      // Announce the phase and its size up front: extraction calls are slow, so without this the
      // client shows an opaque "starting 0/0" until the first call returns. Emitting 0/total lets it
      // render "extract 0/N" immediately so the user can gauge the scale of the run.
      progress.onPhase("extract", 0, total);
      for (Future<ExtractionPassResult> f : futures) {
        ExtractionPassResult result = awaitExtraction(f);
        log.info("ingest extraction chunk={} candidates={} done={}/{} ms={}",
          result.chunkIndex(), result.candidates().size(), done + 1, total, result.millis());
        results.addAll(result.candidates());
        progress.onPhase("extract", ++done, total);
      }
    }
    return results;
  }

  private static ExtractionPassResult extractWithTiming(Semaphore gate, Chunk chunk,
                                                        Callable<List<UnifiedCandidate>> task) throws Exception {
    Timed<List<UnifiedCandidate>> timed = Timed.measure(() -> {
      try {
        return bounded(gate, task);
      } catch (Exception e) {
        throw new ExtractionCallException(e);
      }
    });
    List<UnifiedCandidate> candidates = timed.value() == null ? List.of() : timed.value();
    return new ExtractionPassResult(chunk.index(), candidates, timed.millis());
  }

  private static <T> T bounded(Semaphore gate, Callable<T> task) throws Exception {
    gate.acquire();
    try {
      return task.call();
    } finally {
      gate.release();
    }
  }

  private static ExtractionPassResult awaitExtraction(Future<ExtractionPassResult> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ModelUnavailableException("extraction interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ExtractionCallException && cause.getCause() != null) {
        cause = cause.getCause();
      }
      if (cause instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        throw new ModelUnavailableException("extraction interrupted", cause);
      }
      if (cause instanceof ModelUnavailableException mue) {
        throw mue;
      }
      throw new ModelUnavailableException("extraction failed", cause);
    }
  }

  private static class ExtractionCallException extends RuntimeException {

    private ExtractionCallException(Throwable cause) {
      super(cause);
    }
  }

  /**
   * Merge candidates across samples, de-duplicating by normalized content (case-insensitive,
   * trimmed) while keeping the first occurrence with its classification and provenance.
   */
  private static List<UnifiedCandidate> mergeCandidates(List<UnifiedCandidate> candidates) {
    Map<String, UnifiedCandidate> byContent = new LinkedHashMap<>();
    for (UnifiedCandidate candidate : candidates) {
      if (candidate == null || candidate.content() == null || candidate.content().isBlank()) {
        continue;
      }
      String key = candidate.content().strip().toLowerCase(Locale.ROOT);
      byContent.putIfAbsent(key, candidate);
    }
    return new ArrayList<>(byContent.values());
  }

  /**
   * Build {@code embed_text}: interrogative queries prepended to the declarative content
   * so the stored vector bridges declarative storage and interrogative recall queries.
   */
  private static String buildEmbedText(List<String> interrogativeQueries, String content) {
    if (interrogativeQueries == null || interrogativeQueries.isEmpty()) {
      return content;
    }
    StringBuilder sb = new StringBuilder();
    for (String q : interrogativeQueries) {
      if (q != null && !q.isBlank()) {
        sb.append(q.strip()).append(' ');
      }
    }
    sb.append(content);
    return sb.toString();
  }

  /** Shorten candidate text for single-line diagnostic logs. */
  private static String abbreviate(String content) {
    if (content == null) {
      return "";
    }
    String oneLine = content.strip().replaceAll("\\s+", " ");
    return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 117) + "…";
  }

  /**
   * Explicit single-memory write (POST /memories). No model call: persist directly under the
   * resolved profile (with supersession + vectorization enqueue) and return the stored row.
   */
  public Memory remember(String profileName, Memory memory) {
    Profile profile = store.getOrCreateProfile(profileName);
    MemoryStore.StoreOutcome outcome = store.store(profile.id(), memory);
    log.info("remember profile={} memoryId={} type={} supersededId={} vectorEnqueued={}",
      profileName, outcome.stored().id(), outcome.stored().type(), outcome.supersededId(),
      outcome.enqueuedVector());
    return outcome.stored();
  }
}
