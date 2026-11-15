package dev.alvo.pieria.profile;

import dev.alvo.pieria.api.request.RevisionRequest;
import dev.alvo.pieria.api.response.RevisionResponse;
import dev.alvo.pieria.api.response.TemporalValidity;
import dev.alvo.pieria.api.response.FactHistoryResponse;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.error.ConflictException;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemorySearchPage;
import dev.alvo.pieria.domain.memory.MemorySearchQuery;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.TopicKeyEntry;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.domain.profile.ProfileCount;
import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
import dev.alvo.pieria.harvest.HarvestSourceService;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Owns single-profile use cases that need more than a bare {@link MemoryStore} passthrough: a
 * lookup-or-404 followed by a second storage call. Keeps that lookup-then-act flow (and the
 * {@link NotFoundException} it throws) out of {@code ProfileController}.
 */
@Service
public class ProfileService {

  private final MemoryStore store;
  private final ObjectProvider<HarvestSourceService> harvestSources;

  public ProfileService(MemoryStore store, ObjectProvider<HarvestSourceService> harvestSources) {
    this.store = store;
    this.harvestSources = harvestSources;
  }

  public List<ProfileCount> listSummaries() {
    return store.listProfiles();
  }

  public Profile create(String name) {
    return create(name, null);
  }

  /**
   * Create an empty profile under {@code parentName}, or at top level when it is {@code null}. An
   * unknown parent is a 404 and nothing is created.
   */
  public Profile create(String name, String parentName) {
    String parentId = parentName == null ? null : findOrThrow(parentName).id();
    return store.createProfile(name, parentId);
  }

  /**
   * Move a profile under {@code parentName}, or to top level when it is {@code null}. Moving a
   * profile under itself or under one of its own descendants would make a cycle and is a 409.
   */
  public ProfileCount setParent(String name, String parentName) {
    Profile profile = findOrThrow(name);
    String parentId = null;
    if (parentName != null) {
      Profile parent = findOrThrow(parentName);
      if (store.profileSubtree(profile.id()).contains(parent.id())) {
        throw ConflictException.profileCycle(name, parentName);
      }
      parentId = parent.id();
    }
    store.setProfileParent(profile.id(), parentId);
    return summary(name);
  }

  /** The listing row for one profile, as GET /v1/profiles would show it. */
  public ProfileCount summary(String name) {
    return store.listProfiles().stream()
      .filter(count -> count.profile().name().equals(name))
      .findFirst()
      .orElseThrow(() -> NotFoundException.profile(name));
  }

  public void delete(String name) {
    Profile profile = findOrThrow(name);
    Runnable delete = () -> store.deleteProfile(profile.id());
    HarvestSourceService sources = harvestSources.getIfAvailable();
    if (sources == null) {
      delete.run();
    } else {
      sources.removeAllFor(profile.id(), delete);
    }
  }

  public List<Memory> list(String name, MemoryType typeFilter, String session, boolean includeSuperseded) {
    Profile profile = findOrThrow(name);
    return store.listMemories(profile.id(), typeFilter, session, includeSuperseded);
  }

  /** One page of the console's memory list; the limit must be 1–200. */
  public MemorySearchPage search(String name, MemorySearchQuery query) {
    if (query.limit() < 1 || query.limit() > 200) {
      throw new IllegalArgumentException("limit must be between 1 and 200");
    }
    return store.searchMemories(findOrThrow(name).id(), query);
  }

  /** A single memory of the profile, superseded ones included; forgotten or unknown ids are 404. */
  public Memory get(String name, String memoryId) {
    Profile profile = findOrThrow(name);
    return store.findMemory(profile.id(), memoryId).orElseThrow(() -> NotFoundException.memory(memoryId));
  }

  public List<TopicKeyEntry> topicKeys(String name, String text, MemoryType type, boolean includeSystem, int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100");
    }
    return store.searchTopicKeys(findOrThrow(name).id(), text, type, includeSystem, limit);
  }

  public void forget(String name, String memoryId) {
    Profile profile = findOrThrow(name);
    if (!store.forgetMemory(profile.id(), memoryId)) {
      throw NotFoundException.memory(memoryId);
    }
  }

  public List<ExportRow> export(String name) {
    Profile profile = findOrThrow(name);
    return store.exportProfile(profile.id());
  }

  public List<TemporalValidity> validityHistory(String name, String memoryId) {
    return store.validityHistory(findOrThrow(name).id(), memoryId);
  }

  public List<String> temporalLimitations(boolean explicit) {
    return explicit ? List.of("Unversioned symbol and code-graph channels are omitted.") : List.of();
  }

  public List<TemporalValidity> validity(String memoryId, TemporalSnapshot snapshot) {
    return store.selectedValidity(memoryId, snapshot == null ? store.temporalSnapshot(null, null) : snapshot);
  }

  public List<RevisionResponse> revisions(String name, String memoryId) {
    return store.revisions(findOrThrow(name).id(), memoryId);
  }

  public RevisionResponse revise(String name, String memoryId, RevisionRequest request) {
    return store.revise(findOrThrow(name).id(), memoryId, request);
  }

  public FactHistoryResponse factHistory(String name, String memoryId,
                                         String asOf, String knownAsOf) {
    Profile profile = findOrThrow(name);
    TemporalSnapshot snapshot = store.temporalSnapshot(asOf, knownAsOf);
    MemoryStore.FactHistory history = store.factHistory(profile.id(), memoryId, snapshot)
      .orElseThrow(() -> NotFoundException.memory(memoryId));
    TemporalValidity selected = history.selected().stream().findFirst().orElse(null);
    RevisionResponse selectedRevision = selected == null ? null : history.revisions().stream()
      .filter(revision -> revision.revisionId().equals(selected.revisionId()))
      .findFirst().orElse(null);
    return new FactHistoryResponse(history.factGroup(), selectedRevision, selected,
      history.revisions(), history.segments(), new FactHistoryResponse.TemporalContext(
        snapshot.asOf(), snapshot.knownAsOf(), snapshot.sequence()));
  }

  private Profile findOrThrow(String name) {
    return store.findProfile(name).orElseThrow(() -> NotFoundException.profile(name));
  }
}
