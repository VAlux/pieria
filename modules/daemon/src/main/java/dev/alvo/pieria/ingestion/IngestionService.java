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
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.tools.Timed;
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
   * Verification output: contents that survived (passed or corrected) and the number dropped.
   */
  private record Verification(List<String> verifiedContents, int passed, int corrected, int dropped) {
  }

  /**
   * Store output: the persisted memories plus supersession and vectorization-enqueue counts.
   */
  private record StoreResult(List<Memory> stored, int superseded, int enqueued,
                             int graphExtracted, int graphSkipped, int graphFailed) {
  }

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
    log.debug(
      "ingest chunked profile={} session={} chunks={} detailPass={} detailPassMinMessages={} maxExtractionConcurrency={} chunkMs={}",
      profileName, sessionId, chunks.size(), detailPass, tuning.detailPassMinMessages(),
      Math.max(1, tuning.maxExtractionConcurrency()), chunk.millis());

    Timed<Extraction> extract = Timed.measure(
      () -> extractCandidates(chunks, detailPass, Math.max(1, tuning.maxExtractionConcurrency()), progress));
    Extraction extraction = extract.value();
    log.debug("ingest extracted profile={} session={} rawCandidates={} mergedCandidates={} duplicatesOrBlank={} extractionMs={}",
      profileName, sessionId, extraction.rawCount(), extraction.merged().size(),
      Math.max(0, extraction.rawCount() - extraction.merged().size()), extract.millis());

    Timed<Verification> verify = Timed.measure(() -> verifyCandidates(extraction.merged(), chunks, progress));
    Verification verification = verify.value();
    log.debug("ingest verified profile={} session={} passed={} corrected={} dropped={} verified={} verificationMs={}",
      profileName, sessionId, verification.passed(), verification.corrected(), verification.dropped(),
      verification.verifiedContents().size(), verify.millis());

    Timed<StoreResult> classifyStore =
      Timed.measure(() -> classifyAndStore(profile.id(), sessionId, verification.verifiedContents(), progress));

    StoreResult result = classifyStore.value();

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
      verification.dropped(),
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
        verificationMs={} \
        classifyStoreMs={} \
        totalMs={}""",
      profileName,
      sessionId,
      normalize.millis(),
      messageStore.millis(),
      chunk.millis(),
      extract.millis(),
      verify.millis(),
      classifyStore.millis(),
      Timed.elapsedMillis(totalStart));

    return result.stored();
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
   * Verify each candidate against its source chunk: drop unsupported claims, pass verbatim ones,
   * and substitute the model's correction when it returns one.
   */
  private Verification verifyCandidates(List<ExtractedCandidate> merged, List<Chunk> chunks,
                                        IngestProgressListener progress) {
    Map<Integer, String> transcriptByChunk = new HashMap<>();
    for (Chunk c : chunks) {
      transcriptByChunk.put(c.index(), c.transcript());
    }

    int dropped = 0;
    int passed = 0;
    int corrected = 0;
    int total = merged.size();
    List<String> verifiedContents = new ArrayList<>();
    int candidateIndex = 0;
    for (ExtractedCandidate candidate : merged) {
      candidateIndex++;
      String transcript = transcriptByChunk.getOrDefault(candidate.chunkIndex(), "");
      VerificationResult result = modelGateway.verify(candidate, transcript);
      switch (result.verdict()) {
        case DROP -> dropped++;
        case PASS -> {
          passed++;
          verifiedContents.add(candidate.content());
        }
        case CORRECT -> {
          corrected++;
          String correctedContent = result.content() != null && !result.content().isBlank()
            ? result.content()
            : candidate.content();
          verifiedContents.add(correctedContent);
        }
      }
      log.debug("ingest verification candidate={} chunk={} verdict={}",
        candidateIndex, candidate.chunkIndex(), result.verdict());
      progress.onPhase("verify", candidateIndex, total);
    }

    return new Verification(verifiedContents, passed, corrected, dropped);
  }

  /**
   * Classify and enrich each verified content (topic key + interrogative queries → {@code
   * embed_text}), then store it under the profile with supersession and vectorization enqueue.
   */
  private StoreResult classifyAndStore(String profileId, String sessionId, List<String> verifiedContents,
                                       IngestProgressListener progress) {
    List<Memory> stored = new ArrayList<>(verifiedContents.size());
    int superseded = 0;
    int enqueued = 0;
    int graphExtracted = 0;
    int graphSkipped = 0;
    int graphFailed = 0;
    int total = verifiedContents.size();
    int candidateIndex = 0;
    for (String content : verifiedContents) {
      candidateIndex++;
      if (content == null || content.isBlank()) {
        log.debug("ingest store candidate={} skipped blank content", candidateIndex);
        progress.onPhase("store", candidateIndex, total);
        continue;
      }
      Classification classification = modelGateway.classify(content);
      log.debug("ingest classified candidate={} type={} hasTopicKey={} interrogativeQueries={}",
        candidateIndex, classification.type(), classification.topicKey() != null,
        classification.interrogativeQueries() == null ? 0 : classification.interrogativeQueries().size());

      String embedText = buildEmbedText(classification.interrogativeQueries(), content);
      String payload = classification.payload() == null ? "{}" : classification.payload();
      Memory candidate = new Memory(
        null, sessionId, classification.type(), content, classification.topicKey(),
        null, false, payload, embedText, null);

      // Graph extraction is additive and degradable: a failure here must never roll back or fail the
      // memory write. Tasks are excluded from the graph (as from the vector index). The model call
      // happens here, outside the store transaction.
      GraphFragment graph = GraphFragment.empty();
      if (classification.type() != MemoryType.TASK) {
        try {
          graph = modelGateway.extractGraph(content);
          graphExtracted++;
          log.debug("ingest graph extracted candidate={} entities={} triples={}",
            candidateIndex, graph.allEntities().size(), graph.triples().size());
        } catch (RuntimeException e) {
          graphFailed++;
          log.warn("graph extraction failed; storing memory without graph: {}", e.toString());
        }
      } else {
        graphSkipped++;
        log.debug("ingest graph skipped candidate={} type={}", candidateIndex, classification.type());
      }

      MemoryStore.StoreOutcome outcome = store.store(profileId, candidate, graph);
      stored.add(outcome.stored());
      if (outcome.supersededId() != null) {
        superseded++;
      }
      if (outcome.enqueuedVector()) {
        enqueued++;
      }
      log.debug("ingest stored candidate={} memoryId={} type={} supersededId={} vectorEnqueued={}",
        candidateIndex, outcome.stored().id(), outcome.stored().type(), outcome.supersededId(),
        outcome.enqueuedVector());
      progress.onPhase("store", candidateIndex, total);
    }

    return new StoreResult(stored, superseded, enqueued, graphExtracted, graphSkipped, graphFailed);
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
    log.debug("ingest extraction start chunks={} detailPass={} maxConcurrency={} modelCalls={}",
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
      for (Future<ExtractionPassResult> f : futures) {
        ExtractionPassResult result = awaitExtraction(f);
        log.debug("ingest extraction pass={} chunk={} candidates={} ms={}",
          result.pass(), result.chunkIndex(), result.candidates().size(), result.millis());
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
