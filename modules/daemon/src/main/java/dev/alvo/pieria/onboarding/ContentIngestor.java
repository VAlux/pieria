package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.response.OnboardResult;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.ingestion.ChunkLedgerMode;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.ingestion.GraphMode;
import dev.alvo.pieria.ingestion.IngestionResult;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.tools.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared write path for content sources (markdown, text, pdf, web): turns {@link ContentDocument}s
 * into conversation messages and runs them through the memory-extraction pipeline. Each document is
 * split into section-sized messages (on top-level {@code #}/{@code ##} headings), each prefixed with
 * the document's provenance line so the extractor knows where the content came from, and kept under
 * {@link #MAX_MESSAGE_CHARS} so the ingest chunker never has to re-split a single message.
 *
 * <p><b>Incremental onboarding</b>: every document's pipeline-relevant inputs are hashed
 * ({@link #PIPELINE_VERSION} + extraction samples + provenance + text) and checked against the
 * profile's {@code ingest_ledger}; unchanged documents skip the model pipeline entirely, which is
 * what makes re-onboarding a mostly-unchanged corpus near-free. Changed documents are packed into
 * {@link #BATCH_CHAR_BUDGET}-sized batches, and each batch's ledger rows are written only
 * <em>after</em> its memories are durably stored — so an interrupted onboard resumes at batch
 * granularity instead of redoing hours of inference. {@code refresh} bypasses the ledger check
 * (the rows are still rewritten as batches complete).
 *
 * <p>The {@code sessionId} is fixed ({@value #SESSION_ID}): combined with the pipeline's
 * content-addressed ids, re-onboarding unchanged content produces no duplicate memories.
 */
@Component
public class ContentIngestor {

  private static final Logger log = LoggerFactory.getLogger(ContentIngestor.class);

  /**
   * Stable session id so re-ingesting unchanged content is idempotent (content-addressed).
   */
  public static final String SESSION_ID = "pieria-init";

  /**
   * Salt for the ledger content hashes; bump on prompt/pipeline changes that should force
   * re-extraction of already-onboarded documents (same convention as the code-summary hashes).
   */
  static final String PIPELINE_VERSION = "v1";

  /**
   * Per-message ceiling, under the pipeline's ~10K chunk boundary so it never re-splits a message.
   */
  static final int MAX_MESSAGE_CHARS = 8_000;

  /**
   * Target total text per ingest batch (~4 chunks): big enough to keep cross-document chunk
   * packing for corpora of tiny files, small enough that an interrupted run loses little work.
   */
  static final int BATCH_CHAR_BUDGET = 40_000;

  /**
   * Start of a top-level or second-level ATX heading line.
   */
  private static final Pattern SECTION_HEADING = Pattern.compile("(?m)^#{1,2}\\s");

  private final IngestionService ingestionService;
  private final MemoryStore store;

  public ContentIngestor(IngestionService ingestionService, MemoryStore store) {
    this.ingestionService = ingestionService;
    this.store = store;
  }

  /**
   * Ingest the given documents into {@code profile}, skipping documents whose ledger hash is
   * unchanged (unless {@code refresh}). Documents that yield no non-blank content contribute no
   * messages but are still ledgered as processed; when nothing is extractable the run stores
   * nothing and reports zero.
   *
   * @param sourceType        label for the result and the ledger scope (e.g. {@code "markdown"})
   * @param documents         the discovered content units
   * @param extractionSamples independent extract passes per chunk (null ⇒ profile default)
   * @param refresh           re-ingest every document even when its ledger hash is unchanged
   */
  public OnboardResult ingest(String profile,
                              String sourceType,
                              List<ContentDocument> documents,
                              Integer extractionSamples,
                              boolean refresh,
                              IngestProgressListener progress) {

    String profileId = store.getOrCreateProfile(profile).id();
    Map<String, String> ledger = refresh ? Map.of() : store.ingestLedger(profileId, sourceType);

    Map<String, String> hashByProvenance = new LinkedHashMap<>();
    List<ContentDocument> pending = new ArrayList<>();
    for (ContentDocument doc : documents) {
      String hash = contentHash(doc, extractionSamples);
      hashByProvenance.put(doc.provenance(), hash);
      if (hash.equals(ledger.get(doc.provenance()))) {
        log.debug("onboard {}: unchanged, skipping ({})", sourceType, doc.provenance());
      } else {
        pending.add(doc);
      }
    }
    int skipped = documents.size() - pending.size();
    if (skipped > 0) {
      log.info("onboard {}: {} of {} documents unchanged since the last onboard; skipping them",
        sourceType, skipped, documents.size());
    }
    if (pending.isEmpty()) {
      return OnboardResult.content(sourceType, documents.size(), 0, skipped, 0);
    }

    List<List<ContentDocument>> batches = batchByBudget(pending);
    // Make message FTS useful immediately for the whole source, even while later extraction batches
    // are still running. The normal ingest path repeats these idempotent inserts per batch.
    List<Message> stagedMessages = new ArrayList<>();
    for (ContentDocument doc : pending) {
      for (String content : toMessageContents(doc)) {
        stagedMessages.add(Message.of(SESSION_ID, "user", content));
      }
    }
    int staged = ingestionService.preStageMessages(profile, SESSION_ID, stagedMessages);
    log.info("onboard {}: pre-staged {} raw messages for FTS", sourceType, staged);

    int stored = 0;
    int graphDeferred = 0;
    int batchesDone = 0;
    progress.onPhase("documents", 0, batches.size());
    for (List<ContentDocument> batch : batches) {
      List<Message> messages = new ArrayList<>();
      for (ContentDocument doc : batch) {
        for (String content : toMessageContents(doc)) {
          messages.add(Message.of(SESSION_ID, "user", content));
        }
      }
      if (!messages.isEmpty()) {
        // ChunkLedgerMode.DISABLED: onboarding already skips unchanged documents through the ledger
        // below, and every batch re-chunks from index 0 under the one fixed SESSION_ID, so
        // chunk-level ledger keys would collide across batches.
        IngestionResult result = ingestionService.ingestDetailed(profile, SESSION_ID, messages,
          extractionSamples, GraphMode.DEFERRED, ChunkLedgerMode.DISABLED, progress);
        stored += result.memories().size();
        graphDeferred += result.graphDeferred();
      }
      // Checkpoint only now, after the batch's memories are durably stored: the ledger must never
      // claim work that did not finish, or a crashed run would silently lose those documents.
      Map<String, String> batchHashes = new LinkedHashMap<>();
      for (ContentDocument doc : batch) {
        batchHashes.put(doc.provenance(), hashByProvenance.get(doc.provenance()));
      }
      store.recordIngestLedger(profileId, sourceType, batchHashes);
      progress.onPhase("documents", ++batchesDone, batches.size());
    }
    log.info("onboard {}: core ready stored={} graphDeferred={}", sourceType, stored, graphDeferred);
    return OnboardResult.content(sourceType, documents.size(), stored, skipped, graphDeferred);
  }

  /**
   * The ledger hash of one document: everything that determines what the pipeline would produce
   * for it. The extraction-samples override is part of the hash so a deliberate saturation re-run
   * ({@code --extraction-samples N}) re-extracts documents onboarded with fewer samples.
   */
  static String contentHash(ContentDocument doc, Integer extractionSamples) {
    String samples = extractionSamples == null ? "default" : String.valueOf(Math.max(1, extractionSamples));
    return Hash.hash128(PIPELINE_VERSION, samples,
      doc.provenance() == null ? "" : doc.provenance(),
      doc.text() == null ? "" : doc.text());
  }

  /**
   * Greedily pack documents into batches of at most {@link #BATCH_CHAR_BUDGET} total text chars
   * (an oversized document still forms its own batch). Order is preserved.
   */
  static List<List<ContentDocument>> batchByBudget(List<ContentDocument> documents) {
    List<List<ContentDocument>> batches = new ArrayList<>();
    List<ContentDocument> current = new ArrayList<>();
    int currentChars = 0;
    for (ContentDocument doc : documents) {
      int chars = doc.text() == null ? 0 : doc.text().length();
      if (!current.isEmpty() && currentChars + chars > BATCH_CHAR_BUDGET) {
        batches.add(current);
        current = new ArrayList<>();
        currentChars = 0;
      }
      current.add(doc);
      currentChars += chars;
    }
    if (!current.isEmpty()) {
      batches.add(current);
    }
    return batches;
  }

  /**
   * Split one document into provenance-prefixed message bodies. Blank sections are dropped; oversize
   * sections are hard-split on paragraph boundaries.
   */
  static List<String> toMessageContents(ContentDocument doc) {
    String prefix = doc.provenance() + ":\n\n";
    List<String> out = new ArrayList<>();
    for (String section : splitIntoSections(doc.text())) {
      if (section.isBlank()) {
        continue;
      }
      for (String piece : hardSplit(section, MAX_MESSAGE_CHARS - prefix.length())) {
        if (!piece.isBlank()) {
          out.add(prefix + piece);
        }
      }
    }
    return out;
  }

  /**
   * Split markdown on top-level/second-level headings, keeping each heading with the body that
   * follows it. Text with no such headings yields a single section (the whole document).
   */
  static List<String> splitIntoSections(String markdown) {
    var matcher = SECTION_HEADING.matcher(markdown);
    List<Integer> starts = new ArrayList<>();
    while (matcher.find()) {
      starts.add(matcher.start());
    }
    if (starts.isEmpty()) {
      return List.of(markdown);
    }
    List<String> sections = new ArrayList<>();
    // Preamble before the first heading, if any.
    if (starts.getFirst() > 0) {
      sections.add(markdown.substring(0, starts.get(0)));
    }
    for (int i = 0; i < starts.size(); i++) {
      int from = starts.get(i);
      int to = (i + 1 < starts.size()) ? starts.get(i + 1) : markdown.length();
      sections.add(markdown.substring(from, to));
    }
    return sections;
  }

  /**
   * Split a section into pieces no longer than {@code maxChars}, preferring paragraph
   * ({@code "\n\n"}) boundaries and falling back to a hard character cut for a single huge
   * paragraph. Sections already within the limit are returned as-is.
   */
  static List<String> hardSplit(String section, int maxChars) {
    int limit = Math.max(1, maxChars);
    if (section.length() <= limit) {
      return List.of(section);
    }
    List<String> pieces = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String paragraph : section.split("(?<=\n\n)")) {
      if (paragraph.length() > limit) {
        // Flush whatever is buffered, then chop the oversize paragraph into fixed-width slices.
        if (!current.isEmpty()) {
          pieces.add(current.toString());
          current.setLength(0);
        }
        for (int i = 0; i < paragraph.length(); i += limit) {
          pieces.add(paragraph.substring(i, Math.min(paragraph.length(), i + limit)));
        }
      } else if (current.length() + paragraph.length() > limit) {
        pieces.add(current.toString());
        current.setLength(0);
        current.append(paragraph);
      } else {
        current.append(paragraph);
      }
    }
    if (!current.isEmpty()) {
      pieces.add(current.toString());
    }
    return pieces;
  }
}
