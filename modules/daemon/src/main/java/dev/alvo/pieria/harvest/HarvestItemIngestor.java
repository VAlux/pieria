package dev.alvo.pieria.harvest;

import dev.alvo.pieria.api.request.RevisionRequest;
import dev.alvo.pieria.api.response.MemoryOrigin;
import dev.alvo.pieria.api.response.RevisionResponse;
import dev.alvo.pieria.config.HarvestProperties;
import dev.alvo.pieria.domain.memory.IngestOrigin;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.ingestion.ChunkLedgerMode;
import dev.alvo.pieria.ingestion.GraphMode;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.ingestion.IngestionResult;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.onboarding.ContentDocument;
import dev.alvo.pieria.onboarding.ContentIngestor;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.storage.TemporalConflictException;
import dev.alvo.pieria.tools.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Keeps one source item's memories in step with the item. A changed item is re-extracted and whatever
 * the new version no longer says is retired; a deleted item takes its memories with it.
 */
@Component
public class HarvestItemIngestor {

  private static final Logger log = LoggerFactory.getLogger(HarvestItemIngestor.class);
  private static final String HASH_VERSION = "harvest-v1";
  private static final String PENDING_VERSION = "pending";

  private final HarvestStore harvest;
  private final MemoryStore memories;
  private final IngestionService ingestion;
  private final HarvestProperties properties;

  public HarvestItemIngestor(HarvestStore harvest, MemoryStore memories, IngestionService ingestion,
                             HarvestProperties properties) {
    this.harvest = harvest;
    this.memories = memories;
    this.ingestion = ingestion;
    this.properties = properties;
  }

  /** {@code complete} is false when some model output could not be read, so the item must be retried. */
  private record Extracted(int memoriesStored, boolean complete) {
  }

  /** What an item's text and identity hash to, and how much of it there is. */
  private record Fingerprint(String version, String prefix, int turnCount, long chars, Instant changedAt) {
  }

  /**
   * A new session for each life of an item. Memory ids hash the session, and a forgotten memory stays
   * forgotten, so an item deleted at the source that later returns must not reuse its old ids.
   */
  private static String newSessionId(HarvestSource source, String externalId) {
    return "harvest:" + source.id() + ":" + Hash.hash128(externalId, UUID.randomUUID().toString());
  }

  private static List<String> header(HarvestSource source, String title, String url) {
    return new ArrayList<>(List.of(HASH_VERSION, source.mode().name(), title, url));
  }

  private static void addTurns(List<String> parts, List<HarvestRecord.Turn> turns) {
    for (HarvestRecord.Turn turn : turns) {
      parts.add(turn.id());
      parts.add(turn.author());
      parts.add(turn.timestamp().toString());
      parts.add(turn.text());
    }
  }

  private static String hash(List<String> parts) {
    return Hash.hash128(parts.toArray(String[]::new));
  }

  private static String prefixHash(HarvestSource source, HarvestRecord.ConversationThread thread, int turnCount) {
    List<String> parts = header(source, thread.title(), thread.url());
    addTurns(parts, thread.turns().subList(0, turnCount));
    return hash(parts);
  }

  private static Fingerprint fingerprint(HarvestSource source, HarvestRecord.Item item) {
    return switch (item) {
      case HarvestRecord.Document document -> {
        List<String> parts = header(source, document.title(), document.url());
        parts.add(document.text());
        yield new Fingerprint(hash(parts), null, 0, document.text().length(), document.updatedAt());
      }
      case HarvestRecord.ConversationThread thread -> {
        String whole = prefixHash(source, thread, thread.turns().size());
        long chars = thread.turns().stream().mapToLong(turn -> turn.text().length()).sum();
        yield new Fingerprint(whole, whole, thread.turns().size(), chars, thread.turns().stream()
          .map(HarvestRecord.Turn::timestamp).max(Instant::compareTo).orElseThrow());
      }
    };
  }

  private static List<Message> messages(HarvestSource source, HarvestRecord.Item item, String session) {
    return switch (item) {
      case HarvestRecord.Document document -> {
        String provenance = source.harvesterId() + " — " + MemoryOrigin.oneLine(document.title()) + " (" + document.url() + ")";
        yield ContentIngestor.toMessageContents(new ContentDocument(provenance, document.text())).stream()
          .map(content -> new Message(null, session, "document", content, document.updatedAt()))
          .toList();
      }
      case HarvestRecord.ConversationThread thread -> {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(null, session, "source",
          source.harvesterId() + " thread — " + MemoryOrigin.oneLine(thread.title()) + " (" + thread.url() + ")",
          thread.turns().getFirst().timestamp()));
        for (HarvestRecord.Turn turn : thread.turns()) {
          for (String piece : ContentIngestor.hardSplit(turn.text(), ContentIngestor.MAX_MESSAGE_CHARS)) {
            if (!piece.isBlank()) {
              messages.add(new Message(null, session, MemoryOrigin.oneLine(turn.author()), piece, turn.timestamp()));
            }
          }
        }
        yield messages;
      }
    };
  }

  private static boolean onlyNewTurns(HarvestSource source, HarvestRecord.Item item, HarvestItem stored) {
    return source.mode() == SourceMode.FULL
      && item instanceof HarvestRecord.ConversationThread thread
      && stored != null
      && stored.prefixHash() != null
      && thread.turns().size() > stored.turnCount()
      && prefixHash(source, thread, stored.turnCount()).equals(stored.prefixHash());
  }

  /** Whether the item differs from what is stored, without writing anything or calling a model. */
  public ItemOutcome preview(HarvestSource source, HarvestRecord.Item item) {
    Fingerprint fingerprint = fingerprint(source, item);
    return harvest.findItem(source.id(), item.id())
      .filter(stored -> stored.versionHash().equals(fingerprint.version()))
      .map(stored -> new ItemOutcome(false, 0, fingerprint.chars(), stored.sessionId(), true))
      .orElseGet(() -> new ItemOutcome(true, 0, fingerprint.chars(), null, true));
  }

  public ItemOutcome ingest(HarvestSource source, String profileName, HarvestRecord.Item item, String runId,
                            IngestProgressListener progress) {
    HarvestItem stored = harvest.findItem(source.id(), item.id()).orElse(null);
    Fingerprint fingerprint = fingerprint(source, item);
    if (stored != null && stored.versionHash().equals(fingerprint.version())) {
      harvest.markSeen(source.id(), List.of(item.id()), runId);
      return new ItemOutcome(false, 0, fingerprint.chars(), stored.sessionId(), true);
    }

    String session = stored == null ? newSessionId(source, item.id()) : stored.sessionId();
    if (stored == null) {
      // Recorded first, so a crash during extraction retries under this session instead of leaving
      // memories behind that nothing tracks.
      harvest.upsertItem(new HarvestItem(source.id(), item.id(), session, PENDING_VERSION, null, 0, runId));
    }
    List<Message> messages = messages(source, item, session);
    Extracted extracted = source.mode() == SourceMode.RAW
      ? stageRaw(source, profileName, item, stored, session, messages, fingerprint)
      : extract(source, profileName, item, stored, session, messages, fingerprint, progress);

    if (extracted.complete()) {
      harvest.upsertItem(new HarvestItem(source.id(), item.id(), session, fingerprint.version(), fingerprint.prefix(),
        fingerprint.turnCount(), runId));
    } else {
      log.warn("harvest source={} item={}: the model output could not be read; the cursor is held so the item is reported again",
        source.id(), item.id());
      harvest.markSeen(source.id(), List.of(item.id()), runId);
    }
    return new ItemOutcome(true, extracted.memoriesStored(), fingerprint.chars(), session, extracted.complete());
  }

  private Extracted stageRaw(HarvestSource source, String profileName, HarvestRecord.Item item, HarvestItem stored, String session,
                       List<Message> messages, Fingerprint fingerprint) {
    if (stored != null) {
      memories.deleteSessionMessages(source.profileId(), session);
      retire(source, item.id(), Set.of(), fingerprint.changedAt());
    }
    ingestion.preStageMessages(profileName, session, messages);
    return new Extracted(0, true);
  }

  private Extracted extract(HarvestSource source, String profileName, HarvestRecord.Item item, HarvestItem stored, String session,
                      List<Message> messages, Fingerprint fingerprint, IngestProgressListener progress) {
    boolean append = onlyNewTurns(source, item, stored);
    adoptSessionMemories(source, item.id(), session);
    List<MemoryLink> before = harvest.links(source.id(), item.id());
    Set<String> correctedByUser = correctedByUser(source.profileId(), before);
    if (!append) {
      if (stored != null) {
        memories.deleteSessionMessages(source.profileId(), session);
      }
      memories.clearChunkLedger(source.profileId(), session);
    }
    // A document is re-extracted whole. A thread keeps the chunk ledger, so its next new turn costs
    // only the chunks that turn touches.
    ChunkLedgerMode ledger = item instanceof HarvestRecord.Document ? ChunkLedgerMode.DISABLED : ChunkLedgerMode.ENABLED;
    IngestOrigin origin = new IngestOrigin(source.id(), source.harvesterId(), item.id(), url(item));
    IngestionResult result = ingestion.ingestDetailed(profileName, session, messages, null, GraphMode.DEFERRED, ledger,
      progress, origin, properties.extractionConcurrency());

    Set<String> produced = result.memories().stream().map(Memory::id).collect(Collectors.toCollection(LinkedHashSet::new));
    // Unreadable model output says nothing about the source, so it can't justify retiring anything.
    boolean complete = result.unparseableChunks() == 0;
    if (!append && complete) {
      retire(source, item.id(), produced, fingerprint.changedAt());
    }
    keepUserCorrections(source.profileId(), before, correctedByUser, produced, fingerprint.changedAt());
    adoptSessionMemories(source, item.id(), session);
    return new Extracted(produced.size(), complete);
  }

  private static String url(HarvestRecord.Item item) {
    return switch (item) {
      case HarvestRecord.Document document -> document.url();
      case HarvestRecord.ConversationThread thread -> thread.url();
    };
  }

  /**
   * Links everything live in the item's session. An ingest stores memories chunk by chunk, so one that
   * was interrupted leaves memories that the retry's result won't list.
   */
  private void adoptSessionMemories(HarvestSource source, String externalId, String session) {
    List<MemoryLink> links = new ArrayList<>();
    for (String memoryId : memories.activeMemoryIdsInSession(source.profileId(), session)) {
      if (isUsersOwnText(source.profileId(), memoryId)) {
        continue;
      }
      memories.revisions(source.profileId(), memoryId).stream()
        .filter(HarvestItemIngestor::inForce)
        .forEach(revision -> links.add(new MemoryLink(memoryId, revision.assertionId(), revision.revisionId())));
    }
    harvest.addLinks(source.id(), externalId, links);
  }

  /**
   * Whether this memory only exists because the user corrected another one. A correction stores its
   * text as a new memory in the same session, and every revision of that memory corrects an earlier one.
   */
  private boolean isUsersOwnText(String profileId, String memoryId) {
    List<RevisionResponse> own = memories.revisions(profileId, memoryId).stream()
      .filter(revision -> revision.memoryId().equals(memoryId))
      .toList();
    return !own.isEmpty() && own.stream().allMatch(revision -> revision.corrects() != null);
  }

  private static boolean inForce(RevisionResponse revision) {
    return revision.recordedTo() == null && !revision.invalidated() && revision.validTo() == null;
  }

  /** Memories whose current revision is no longer the one harvested: the user has changed them. */
  private Set<String> correctedByUser(String profileId, List<MemoryLink> links) {
    Set<String> corrected = new LinkedHashSet<>();
    for (MemoryLink link : links) {
      boolean unchanged = memories.revisions(profileId, link.memoryId()).stream()
        .anyMatch(revision -> revision.recordedTo() == null && revision.revisionId().equals(link.revisionId()));
      if (!unchanged) {
        corrected.add(link.memoryId());
      }
    }
    return corrected;
  }

  /**
   * Re-extracting a sentence the user corrected adds a new assertion of the original wording. Ending it
   * here keeps an edit elsewhere in the item from bringing back what the user removed.
   */
  private void keepUserCorrections(String profileId, List<MemoryLink> before, Set<String> correctedByUser, Set<String> produced,
                                   Instant changedAt) {
    Set<String> known = before.stream().map(MemoryLink::assertionId).collect(Collectors.toSet());
    for (String memoryId : correctedByUser) {
      if (!produced.contains(memoryId)) {
        continue;
      }
      memories.revisions(profileId, memoryId).stream()
        .filter(HarvestItemIngestor::inForce)
        .filter(revision -> !known.contains(revision.assertionId()))
        .forEach(revision -> {
          try {
            memories.revise(profileId, memoryId,
              new RevisionRequest(revision.assertionId(), revision.revisionId(), null, null, null, changedAt.toString()));
          } catch (TemporalConflictException e) {
            log.debug("harvest: memory {} changed while its correction was being kept", memoryId);
          }
        });
    }
  }

  /**
   * Ends the validity of linked memories the new version no longer says, keeping them visible to
   * historical queries. A revision conflict means the user has changed the memory since it was
   * harvested, which makes it theirs, so it is left alone and its link is kept to record that.
   */
  private void retire(HarvestSource source, String externalId, Set<String> produced, Instant changedAt) {
    List<MemoryLink> stale = harvest.links(source.id(), externalId).stream()
      .filter(link -> !produced.contains(link.memoryId()))
      .toList();
    Set<String> keep = new LinkedHashSet<>();
    for (MemoryLink link : stale) {
      try {
        memories.revise(source.profileId(), link.memoryId(),
          new RevisionRequest(link.assertionId(), link.revisionId(), null, null, null, changedAt.toString()));
      } catch (TemporalConflictException e) {
        log.debug("harvest: memory {} was revised since it was harvested; leaving it", link.memoryId());
        keep.add(link.memoryId());
      }
    }
    harvest.removeLinks(source.id(), externalId,
      stale.stream().map(MemoryLink::memoryId).filter(id -> !keep.contains(id)).collect(Collectors.toSet()));
  }

  /**
   * Deleted at the source means gone: memories, raw text and all tracking of the item. Memories are
   * found by session, not by link, so earlier versions and interrupted ingests go too. Text the user
   * wrote as a correction stays, because it is theirs rather than the source's.
   */
  public void delete(HarvestSource source, String externalId) {
    harvest.findItem(source.id(), externalId).ifPresent(item -> {
      memories.activeMemoryIdsInSession(source.profileId(), item.sessionId()).stream()
        .filter(memoryId -> !isUsersOwnText(source.profileId(), memoryId))
        .forEach(memoryId -> memories.forgetMemory(source.profileId(), memoryId));
      memories.deleteSessionMessages(source.profileId(), item.sessionId());
      memories.clearChunkLedger(source.profileId(), item.sessionId());
      harvest.deleteItem(source.id(), externalId);
    });
  }
}
