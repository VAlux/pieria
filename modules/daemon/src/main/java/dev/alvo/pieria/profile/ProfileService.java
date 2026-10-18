package dev.alvo.pieria.profile;

import dev.alvo.pieria.api.request.RevisionRequest;
import dev.alvo.pieria.api.response.RevisionResponse;
import dev.alvo.pieria.api.response.TemporalValidity;
import dev.alvo.pieria.api.response.FactHistoryResponse;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.domain.profile.ProfileCount;
import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
import dev.alvo.pieria.storage.MemoryStore;
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

  public ProfileService(MemoryStore store) {
    this.store = store;
  }

  public List<ProfileCount> listSummaries() {
    return store.listProfiles();
  }

  public Profile create(String name) {
    return store.createProfile(name);
  }

  public void delete(String name) {
    Profile profile = findOrThrow(name);
    store.deleteProfile(profile.id());
  }

  public List<Memory> list(String name, MemoryType typeFilter, String session, boolean includeSuperseded) {
    Profile profile = findOrThrow(name);
    return store.listMemories(profile.id(), typeFilter, session, includeSuperseded);
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
