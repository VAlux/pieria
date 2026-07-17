package dev.alvo.pieria.profile;

import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.domain.graph.GraphSnapshot;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.profile.Profile;
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

  public GraphSnapshot graph(String name) {
    Profile profile = findOrThrow(name);
    return store.graphSnapshot(profile.id());
  }

  public List<ExportRow> export(String name) {
    Profile profile = findOrThrow(name);
    return store.exportProfile(profile.id());
  }

  private Profile findOrThrow(String name) {
    return store.findProfile(name).orElseThrow(() -> NotFoundException.profile(name));
  }
}
