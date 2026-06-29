package dev.alvo.pieria.ingestion;

import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.ingestion.model.Chunk;
import dev.alvo.pieria.ingestion.model.Classification;
import dev.alvo.pieria.ingestion.model.ExtractedCandidate;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.ingestion.model.VerificationResult;
import dev.alvo.pieria.ingestion.model.VerificationVerdict;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;
import dev.alvo.pieria.storage.MemoryStore;
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
 * Write path: normalize the transcript, store raw messages, chunk, run parallel
 * full + detail extraction, verify each candidate against its source chunk, classify and enrich
 * (topic key + interrogative queries → {@code embed_text}), then store with supersession and
 * enqueue vectorization. Returns to the caller before vectorization completes (async worker).
 * Explicit single-memory writes ({@link #remember}) bypass the model entirely.
 */
@Service
public class IngestionService {

  private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

  private final MemoryStore store;
  private final ModelGateway modelGateway;
  private final TranscriptNormalizer normalizer;
  private final Chunker chunker;
  private final EffectiveConfigResolver configResolver;

  /**
   * Merged extraction output: the raw candidate count (for logging) and the de-duplicated list.
   */
  private record Extraction(int rawCount, List<ExtractedCandidate> merged) {
  }

  /**
   * Combined verify+classify+store output: per-verdict counts plus the persisted memories and their
   * supersession / vectorization-enqueue / graph tallies.
   */
  private record VerifyStoreResult(List<Memory> stored, int passed, int corrected, int dropped,
                                   int superseded, int enqueued,
                                   int graphExtracted, int graphSkipped, int graphFailed) {
  }

  /** Outcome of persisting one verified candidate (classify + graph + store). */
  private record StoredOne(Memory stored, boolean superseded, boolean enqueued, GraphOutcome graph) {
  }

  /** Whether graph extraction ran, was skipped (tasks), or failed (degraded, memory still stored). */
  private enum GraphOutcome {EXTRACTED, SKIPPED, FAILED}

  /**
   * Result of one extraction model call, keeping pass/chunk metadata for detailed logging.
   */
  private record ExtractionPassResult(String pass, int chunkIndex, List<ExtractedCandidate> candidates, long millis) {
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
    return ingest(profileName, sessionId, messages, IngestProgressListener.noop());
  }

  /**
   * As {@link #ingest(String, String, List)}, reporting coarse per-phase progress through
   * {@code progress} so a long-running ingest can be observed while it runs.
   */
  public List<Memory> ingest(String profileName, String sessionId, List<Message> messages,
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
      return List.of();
    }

    // Per-profile effective tuning: global properties overlaid with any pushed overrides.
    PieriaProperties.Ingestion tuning = configResolver.resolve(profile.id()).ingestion();

    boolean detailPass = normalized.size() >= Math.max(1, tuning.detailPassMinMessages());
    Timed<List<Chunk>> chunk = Timed.measure(() -> chunker.chunk(normalized, tuning));
    List<Chunk> chunks = chunk.value();
    log.info(
      "ingest chunked profile={} session={} chunks={} detailPass={} detailPassMinMessages={} maxExtractionConcurrency={} chunkMs={}",
      profileName, sessionId, chunks.size(), detailPass, tuning.detailPassMinMessages(),
      Math.max(1, tuning.maxExtractionConcurrency()), chunk.millis());

    Timed<Extraction> extract = Timed.measure(
      () -> extractCandidates(chunks, detailPass, Math.max(1, tuning.maxExtractionConcurrency()), progress));
    Extraction extraction = extract.value();
    log.info("ingest extracted profile={} session={} rawCandidates={} mergedCandidates={} duplicatesOrBlank={} extractionMs={}",
      profileName, sessionId, extraction.rawCount(), extraction.merged().size(),
      Math.max(0, extraction.rawCount() - extraction.merged().size()), extract.millis());
    if (extraction.merged().isEmpty()) {
      log.warn("ingest extraction produced no candidates profile={} session={} — the model returned no parseable"
        + " memories for any chunk (check for unparseable-output warnings above); nothing will be stored",
        profileName, sessionId);
    }

    // Verify + classify + store as one stage: each candidate is classified and persisted the moment
    // its verification passes, so an interrupted run keeps every memory it finished (the task is
    // in-memory and dies on daemon restart — incremental storage is what makes partial progress
    // durable). Verification model calls run bounded-parallel; the store write stays single-threaded.
    Timed<VerifyStoreResult> verifyStore = Timed.measure(() -> verifyClassifyStore(
      extraction.merged(), chunks, profile.id(), sessionId,
      Math.max(1, tuning.maxExtractionConcurrency()), progress));
    VerifyStoreResult result = verifyStore.value();
    log.info("ingest verified profile={} session={} passed={} corrected={} dropped={} stored={} verifyStoreMs={}",
      profileName, sessionId, result.passed(), result.corrected(), result.dropped(),
      result.stored().size(), verifyStore.millis());
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
        graphFailed={}""",
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
      result.graphFailed());

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

    return result.stored();
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
   * Run extraction over the chunks (full pass, plus the detail pass when enabled) and merge the
   * candidates, retaining the raw count for logging.
   */
  private Extraction extractCandidates(List<Chunk> chunks, boolean detailPass, int maxExtractionConcurrency,
                                       IngestProgressListener progress) {
    List<ExtractedCandidate> extracted = runExtraction(chunks, detailPass, maxExtractionConcurrency, progress);
    List<ExtractedCandidate> merged = mergeCandidates(extracted);
    return new Extraction(extracted.size(), merged);
  }

  /**
   * Verify each candidate against its source chunk, then classify and store every survivor
   * <em>immediately</em> — so each memory is durably persisted the moment its verification passes,
   * and an interrupted run keeps everything it finished.
   *
   * <p>Verification model calls are independent and read-only, so they run bounded-parallel on
   * virtual threads (gated by {@code maxConcurrency}, like extraction). Results are folded back in
   * submission order on this single thread, which also performs the classify + store for each
   * survivor — keeping the daemon's single-writer invariant and deterministic supersession ordering.
   */
  private VerifyStoreResult verifyClassifyStore(List<ExtractedCandidate> merged, List<Chunk> chunks,
                                                String profileId, String sessionId,
                                                int maxConcurrency, IngestProgressListener progress) {
    int total = merged.size();
    if (total == 0) {
      return new VerifyStoreResult(List.of(), 0, 0, 0, 0, 0, 0, 0, 0);
    }

    Map<Integer, String> transcriptByChunk = new HashMap<>();
    for (Chunk c : chunks) {
      transcriptByChunk.put(c.index(), c.transcript());
    }

    // Group candidates by source chunk (first-seen order). Each chunk is verified in ONE batched
    // call — the transcript is sent once, not re-sent per candidate — which is what collapses the
    // verify phase from one call per candidate to one call per chunk.
    LinkedHashMap<Integer, List<ExtractedCandidate>> byChunk = new LinkedHashMap<>();
    for (ExtractedCandidate candidate : merged) {
      byChunk.computeIfAbsent(candidate.chunkIndex(), k -> new ArrayList<>()).add(candidate);
    }

    int passed = 0;
    int corrected = 0;
    int dropped = 0;
    int superseded = 0;
    int enqueued = 0;
    int graphExtracted = 0;
    int graphSkipped = 0;
    int graphFailed = 0;
    List<Memory> stored = new ArrayList<>();

    Semaphore gate = new Semaphore(maxConcurrency);
    try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
      // One batched verify call per chunk, bounded-parallel across chunks.
      List<Integer> chunkOrder = new ArrayList<>(byChunk.keySet());
      Map<Integer, Future<List<VerificationResult>>> verifyFutures = new LinkedHashMap<>();
      for (Integer chunkIndex : chunkOrder) {
        List<ExtractedCandidate> group = byChunk.get(chunkIndex);
        String transcript = transcriptByChunk.getOrDefault(chunkIndex, "");
        verifyFutures.put(chunkIndex,
          exec.submit(() -> bounded(gate, () -> modelGateway.verifyAll(group, transcript))));
      }

      // Announce the phase up front (see extraction) and fold per chunk in submission order.
      progress.onPhase("verify", 0, total);
      int done = 0;
      for (Integer chunkIndex : chunkOrder) {
        List<ExtractedCandidate> group = byChunk.get(chunkIndex);
        List<VerificationResult> verdicts = awaitVerification(verifyFutures.get(chunkIndex));

        // Resolve this chunk's survivors, counting verdicts and logging drops.
        List<String> survivors = new ArrayList<>();
        for (int j = 0; j < group.size(); j++) {
          ExtractedCandidate candidate = group.get(j);
          VerificationResult result = j < verdicts.size() ? verdicts.get(j)
            : new VerificationResult(VerificationVerdict.DROP, "", "no verdict returned");

          String content = null;
          switch (result.verdict()) {
            case DROP -> dropped++;
            case PASS -> {
              passed++;
              content = candidate.content();
            }
            case CORRECT -> {
              corrected++;
              content = result.content() != null && !result.content().isBlank()
                ? result.content()
                : candidate.content();
            }
          }
          // Drops are the usual reason a profile stays empty, so log them at info with the model's
          // reason; pass/correct stay at debug to keep healthy runs quiet.
          if (result.verdict() == VerificationVerdict.DROP) {
            log.info("ingest verification dropped chunk={} reason={} content=\"{}\"",
              candidate.chunkIndex(), result.reason(), abbreviate(candidate.content()));
          } else {
            log.debug("ingest verification chunk={} verdict={} reason={}",
              candidate.chunkIndex(), result.verdict(), result.reason());
          }
          if (content != null && !content.isBlank()) {
            survivors.add(content);
          }
          progress.onPhase("verify", ++done, total);
        }

        // Batch-classify this chunk's survivors (one call), batch graph-extract the non-task ones
        // (one call), then store each. Storing per chunk keeps progress durable: a finished chunk's
        // memories survive an interrupted run.
        if (!survivors.isEmpty()) {
          List<Classification> classifications = new ArrayList<>(modelGateway.classifyAll(survivors));
          while (classifications.size() < survivors.size()) {
            classifications.add(modelGateway.classify(survivors.get(classifications.size())));
          }

          // Tasks are excluded from the graph (as from the vector index); the rest are graph-extracted
          // in one batched, degradable call.
          List<String> graphContents = new ArrayList<>();
          for (int k = 0; k < survivors.size(); k++) {
            if (classifications.get(k).type() != MemoryType.TASK) {
              graphContents.add(survivors.get(k));
            }
          }
          List<GraphFragment> graphs;
          boolean graphBatchFailed = false;
          try {
            graphs = modelGateway.extractGraphAll(graphContents);
          } catch (RuntimeException e) {
            log.warn("graph extraction batch failed; storing this chunk's memories without graph: {}", e.toString());
            graphs = List.of();
            graphBatchFailed = true;
          }

          int g = 0;
          for (int k = 0; k < survivors.size(); k++) {
            String content = survivors.get(k);
            Classification classification = classifications.get(k);
            GraphFragment graph = GraphFragment.empty();
            GraphOutcome graphOutcome;
            if (classification.type() == MemoryType.TASK) {
              graphOutcome = GraphOutcome.SKIPPED;
            } else if (graphBatchFailed) {
              graphOutcome = GraphOutcome.FAILED;
              g++;
            } else {
              graph = g < graphs.size() ? graphs.get(g) : GraphFragment.empty();
              graphOutcome = GraphOutcome.EXTRACTED;
              g++;
            }

            StoredOne s = storeMemory(profileId, sessionId, content, classification, graph, graphOutcome,
              k + 1, survivors.size());
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
            }
          }
        }
      }
    }

    return new VerifyStoreResult(stored, passed, corrected, dropped,
      superseded, enqueued, graphExtracted, graphSkipped, graphFailed);
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
   */
  private StoredOne storeMemory(String profileId, String sessionId, String content,
                                Classification classification, GraphFragment graph, GraphOutcome graphOutcome,
                                int index, int total) {
    log.debug("ingest classified candidate={}/{} type={} hasTopicKey={} interrogativeQueries={} graph={}",
      index, total, classification.type(), classification.topicKey() != null,
      classification.interrogativeQueries() == null ? 0 : classification.interrogativeQueries().size(),
      graphOutcome);

    String embedText = buildEmbedText(classification.interrogativeQueries(), content);
    String payload = classification.payload() == null ? "{}" : classification.payload();
    Memory candidate = new Memory(
      null, sessionId, classification.type(), content, classification.topicKey(),
      null, false, payload, embedText, null);

    MemoryStore.StoreOutcome outcome = store.store(profileId, candidate, graph);
    log.debug("ingest stored candidate={}/{} memoryId={} type={} supersededId={} vectorEnqueued={}",
      index, total, outcome.stored().id(), outcome.stored().type(), outcome.supersededId(),
      outcome.enqueuedVector());
    return new StoredOne(outcome.stored(), outcome.supersededId() != null, outcome.enqueuedVector(), graphOutcome);
  }

  /**
   * Run the full pass over all chunks, plus the detail pass when enabled, with parallelism bounded
   * by {@code maxExtractionConcurrency}. Blocking model calls run on virtual threads.
   */
  private List<ExtractedCandidate> runExtraction(List<Chunk> chunks, boolean detailPass, int maxExtractionConcurrency,
                                                 IngestProgressListener progress) {
    if (chunks.isEmpty()) {
      log.debug("ingest extraction skipped because there are no chunks");
      return List.of();
    }
    log.info("ingest extraction start chunks={} detailPass={} maxConcurrency={} modelCalls={}",
      chunks.size(), detailPass, maxExtractionConcurrency, detailPass ? chunks.size() * 2 : chunks.size());

    Semaphore gate = new Semaphore(maxExtractionConcurrency);
    List<ExtractedCandidate> results = new ArrayList<>();

    try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<ExtractionPassResult>> futures = new ArrayList<>();
      for (Chunk chunk : chunks) {
        futures.add(exec.submit(() -> extractWithTiming(gate, "full", chunk, () -> modelGateway.extract(chunk))));
        if (detailPass) {
          futures.add(exec.submit(() -> extractWithTiming(gate, "detail", chunk, () -> modelGateway.extractDetail(chunk))));
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
        log.info("ingest extraction pass={} chunk={} candidates={} done={}/{} ms={}",
          result.pass(), result.chunkIndex(), result.candidates().size(), done + 1, total, result.millis());
        results.addAll(result.candidates());
        progress.onPhase("extract", ++done, total);
      }
    }
    return results;
  }

  private static ExtractionPassResult extractWithTiming(Semaphore gate, String pass, Chunk chunk,
                                                        Callable<List<ExtractedCandidate>> task) throws Exception {
    Timed<List<ExtractedCandidate>> timed = Timed.measure(() -> {
      try {
        return bounded(gate, task);
      } catch (Exception e) {
        throw new ExtractionCallException(e);
      }
    });
    List<ExtractedCandidate> candidates = timed.value() == null ? List.of() : timed.value();
    return new ExtractionPassResult(pass, chunk.index(), candidates, timed.millis());
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
   * Merge full- and detail-pass candidates, de-duplicating by normalized content (case-insensitive,
   * trimmed) while keeping the first occurrence and its provenance.
   */
  private static List<ExtractedCandidate> mergeCandidates(List<ExtractedCandidate> candidates) {
    Map<String, ExtractedCandidate> byContent = new LinkedHashMap<>();
    for (ExtractedCandidate candidate : candidates) {
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
