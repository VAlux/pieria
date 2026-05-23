package dev.alvo.pieria.ingestion;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.Chunk;
import dev.alvo.pieria.domain.Classification;
import dev.alvo.pieria.domain.ExtractedCandidate;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.Profile;
import dev.alvo.pieria.domain.VerificationResult;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.ModelUnavailableException;
import dev.alvo.pieria.storage.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.time.Instant;

/**
 * Phase 2 write path (SPEC 6): normalize the transcript, store raw messages, chunk, run parallel
 * full + detail extraction, verify each candidate against its source chunk, classify and enrich
 * (topic key + interrogative queries → {@code embed_text}), then store with supersession and
 * enqueue vectorization. Returns to the caller before vectorization completes (async worker).
 * Explicit single-memory writes ({@link #remember}) bypass the model entirely.
 */
@Service
@org.springframework.context.annotation.Profile("!shim")
public class IngestionService {

  private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

  private final MemoryStore store;
  private final ModelGateway modelGateway;
  private final TranscriptNormalizer normalizer;
  private final Chunker chunker;
  private final int maxExtractionConcurrency;
  private final int detailPassMinMessages;

  public IngestionService(MemoryStore store,
                          ModelGateway modelGateway,
                          TranscriptNormalizer normalizer,
                          Chunker chunker,
                          PieriaProperties properties) {
    this.store = store;
    this.modelGateway = modelGateway;
    this.normalizer = normalizer;
    this.chunker = chunker;
    PieriaProperties.Ingestion ingestion = properties.ingestion();
    this.maxExtractionConcurrency = Math.max(1, ingestion.maxExtractionConcurrency());
    this.detailPassMinMessages = Math.max(1, ingestion.detailPassMinMessages());
  }

  /**
   * Ingest a conversation through the full pipeline. Idempotent: re-ingesting the same transcript
   * yields the same content-addressed messages and memories (insert-or-ignore).
   */
  public List<Memory> ingest(String profileName, String sessionId, List<Message> messages) {
    Profile profile = store.getOrCreateProfile(profileName);
    Instant requestTime = Instant.now();

    // Stamp the session id onto messages that lack one, then validate/normalize.
    List<Message> withSession = new ArrayList<>(messages == null ? 0 : messages.size());
    if (messages != null) {
      for (Message m : messages) {
        withSession.add(m.sessionId() == null
          ? new Message(m.id(), sessionId, m.role(), m.content(), m.createdAt())
          : m);
      }
    }
    List<Message> normalized = normalizer.normalize(withSession, requestTime);

    // Store raw messages first so ingest is inspectable even when nothing is extracted.
    store.insertMessages(profile.id(), sessionId, normalized);
    if (normalized.isEmpty()) {
      log.info("ingest profile={} session={} messages=0 — nothing to extract", profileName, sessionId);
      return List.of();
    }

    List<Chunk> chunks = chunker.chunk(normalized);
    boolean detailPass = normalized.size() >= detailPassMinMessages;

    List<ExtractedCandidate> extracted = runExtraction(chunks, detailPass);
    List<ExtractedCandidate> merged = mergeCandidates(extracted);

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

      MemoryStore.StoreOutcome outcome = store.store(profile.id(), candidate);
      stored.add(outcome.stored());
      if (outcome.supersededId() != null) {
        superseded++;
      }
      if (outcome.enqueuedVector()) {
        enqueued++;
      }
    }

    log.info("ingest profile={} session={} messages={} chunks={} extracted={} merged={} dropped={} stored={} superseded={} vectorJobs={}",
      profileName, sessionId, normalized.size(), chunks.size(), extracted.size(), merged.size(),
      dropped, stored.size(), superseded, enqueued);

    return stored;
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

  private static List<ExtractedCandidate> bounded(Semaphore gate,
                                                  java.util.concurrent.Callable<List<ExtractedCandidate>> task)
    throws Exception {
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
    for (ExtractedCandidate c : candidates) {
      if (c == null || c.content() == null || c.content().isBlank()) {
        continue;
      }
      String key = c.content().strip().toLowerCase(Locale.ROOT);
      byContent.putIfAbsent(key, c);
    }
    return new ArrayList<>(byContent.values());
  }

  /**
   * Build {@code embed_text} (SPEC 8.1): interrogative queries prepended to the declarative content
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
