package dev.alvo.pieria.ingestion;

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
  private final int maxExtractionConcurrency;
  private final int detailPassMinMessages;

  /**
   * Merged extraction output: the raw candidate count (for logging) and the de-duplicated list.
   */
  private record Extraction(int rawCount, List<ExtractedCandidate> merged) {
  }

  /**
   * Verification output: contents that survived (passed or corrected) and the number dropped.
   */
  private record Verification(List<String> verifiedContents, int dropped) {
  }

  /**
   * Store output: the persisted memories plus supersession and vectorization-enqueue counts.
   */
  private record StoreResult(List<Memory> stored, int superseded, int enqueued) {
  }

  public IngestionService(MemoryStore store,
                          ModelGateway modelGateway,
                          TranscriptNormalizer normalizer,
                          Chunker chunker,
                          PieriaProperties properties) {
    this.store = store;
    this.modelGateway = modelGateway;
    this.normalizer = normalizer;
    this.chunker = chunker;
    this.maxExtractionConcurrency = Math.max(1, properties.ingestion().maxExtractionConcurrency());
    this.detailPassMinMessages = Math.max(1, properties.ingestion().detailPassMinMessages());
  }

  /**
   * Ingest a conversation through the full pipeline: normalize → store raw messages → chunk →
   * extract → verify → classify and store. Idempotent — re-ingesting the same transcript yields
   * the same content-addressed messages and memories (insert-or-ignore). Each stage is timed and
   * reported in the latency log line; vectorization is enqueued, not awaited.
   */
  public List<Memory> ingest(String profileName, String sessionId, List<Message> messages) {
    long totalStart = System.nanoTime();
    Profile profile = store.getOrCreateProfile(profileName);

    Timed<List<Message>> normalize = Timed.measure(() -> normalizeMessages(messages, sessionId));
    List<Message> normalized = normalize.value();

    // Store raw messages first so ingest is inspectable even when nothing is extracted.
    Timed<Void> messageStore =
      Timed.measure(() -> store.insertMessages(profile.id(), sessionId, normalized));
    if (normalized.isEmpty()) {
      log.info("ingest profile={} session={} messages=0 normalizeMs={} messageStoreMs={} totalMs={} — nothing to extract",
        profileName, sessionId, normalize.millis(), messageStore.millis(), Timed.elapsedMillis(totalStart));
      return List.of();
    }

    boolean detailPass = normalized.size() >= detailPassMinMessages;
    Timed<List<Chunk>> chunk = Timed.measure(() -> chunker.chunk(normalized));
    List<Chunk> chunks = chunk.value();

    Timed<Extraction> extract = Timed.measure(() -> extractCandidates(chunks, detailPass));
    Extraction extraction = extract.value();

    Timed<Verification> verify = Timed.measure(() -> verifyCandidates(extraction.merged(), chunks));
    Verification verification = verify.value();

    Timed<StoreResult> classifyStore =
      Timed.measure(() -> classifyAndStore(profile.id(), sessionId, verification.verifiedContents()));

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
        vectorJobs={}""",
      profileName,
      sessionId,
      normalized.size(),
      chunks.size(),
      extraction.rawCount(),
      extraction.merged().size(),
      verification.dropped(),
      result.stored().size(),
      result.superseded(),
      result.enqueued());

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
  private Extraction extractCandidates(List<Chunk> chunks, boolean detailPass) {
    List<ExtractedCandidate> extracted = runExtraction(chunks, detailPass);
    List<ExtractedCandidate> merged = mergeCandidates(extracted);
    return new Extraction(extracted.size(), merged);
  }

  /**
   * Verify each candidate against its source chunk: drop unsupported claims, pass verbatim ones,
   * and substitute the model's correction when it returns one.
   */
  private Verification verifyCandidates(List<ExtractedCandidate> merged, List<Chunk> chunks) {
    Map<Integer, String> transcriptByChunk = new HashMap<>();
    for (Chunk c : chunks) {
      transcriptByChunk.put(c.index(), c.transcript());
    }

    int dropped = 0;
    List<String> verifiedContents = new ArrayList<>();
    for (ExtractedCandidate candidate : merged) {
      String transcript = transcriptByChunk.getOrDefault(candidate.chunkIndex(), "");
      VerificationResult result = modelGateway.verify(candidate, transcript);
      switch (result.verdict()) {
        case DROP -> dropped++;
        case PASS -> verifiedContents.add(candidate.content());
        case CORRECT -> {
          String corrected = result.content() != null && !result.content().isBlank()
            ? result.content()
            : candidate.content();
          verifiedContents.add(corrected);
        }
      }
    }

    return new Verification(verifiedContents, dropped);
  }

  /**
   * Classify and enrich each verified content (topic key + interrogative queries → {@code
   * embed_text}), then store it under the profile with supersession and vectorization enqueue.
   */
  private StoreResult classifyAndStore(String profileId, String sessionId, List<String> verifiedContents) {
    List<Memory> stored = new ArrayList<>(verifiedContents.size());
    int superseded = 0;
    int enqueued = 0;
    for (String content : verifiedContents) {
      if (content == null || content.isBlank()) {
        continue;
      }
      Classification classification = modelGateway.classify(content);
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
        } catch (RuntimeException e) {
          log.warn("graph extraction failed; storing memory without graph: {}", e.toString());
        }
      }

      MemoryStore.StoreOutcome outcome = store.store(profileId, candidate, graph);
      stored.add(outcome.stored());
      if (outcome.supersededId() != null) {
        superseded++;
      }
      if (outcome.enqueuedVector()) {
        enqueued++;
      }
    }

    return new StoreResult(stored, superseded, enqueued);
  }

  /**
   * Run the full pass over all chunks, plus the detail pass when enabled, with parallelism bounded
   * by {@code maxExtractionConcurrency}. Blocking model calls run on virtual threads.
   */
  private List<ExtractedCandidate> runExtraction(List<Chunk> chunks, boolean detailPass) {
    if (chunks.isEmpty()) {
      return List.of();
    }
    Semaphore gate = new Semaphore(maxExtractionConcurrency);
    List<ExtractedCandidate> results = new ArrayList<>();

    try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<List<ExtractedCandidate>>> futures = new ArrayList<>();
      for (Chunk chunk : chunks) {
        futures.add(exec.submit(() -> bounded(gate, () -> modelGateway.extract(chunk))));
        if (detailPass) {
          futures.add(exec.submit(() -> bounded(gate, () -> modelGateway.extractDetail(chunk))));
        }
      }
      for (Future<List<ExtractedCandidate>> f : futures) {
        results.addAll(awaitCandidates(f));
      }
    }
    return results;
  }

  private static List<ExtractedCandidate> bounded(Semaphore gate, Callable<List<ExtractedCandidate>> task) throws Exception {
    gate.acquire();
    try {
      return task.call();
    } finally {
      gate.release();
    }
  }

  private static List<ExtractedCandidate> awaitCandidates(Future<List<ExtractedCandidate>> future) {
    try {
      List<ExtractedCandidate> value = future.get();
      return value == null ? List.of() : value;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ModelUnavailableException("extraction interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ModelUnavailableException mue) {
        throw mue;
      }
      throw new ModelUnavailableException("extraction failed", cause);
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
    return store.store(profile.id(), memory).stored();
  }
}
