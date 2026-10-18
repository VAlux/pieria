package dev.alvo.pieria.api;

import dev.alvo.pieria.profile.SessionService;
import dev.alvo.pieria.audit.AuditEvent;
import dev.alvo.pieria.audit.AuditQuery;
import dev.alvo.pieria.audit.AuditStore;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.SessionActivity;
import dev.alvo.pieria.domain.profile.Profile;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionServiceTests {

  private final StubMemoryStore store = new StubMemoryStore();

  private final AuditStore audit = new AuditStore() {
    @Override public void append(AuditEvent event) { }
    @Override public List<AuditEvent> search(String profileName, AuditQuery query) { return List.of(); }
    @Override public Optional<AuditEvent> find(String profileName, String id) { return Optional.empty(); }
    @Override public void deleteForProfile(String profileId, String profileName) { }
    @Override public Map<String, String> harnessBySession(String profileName, Collection<String> sessionIds) {
      return Map.of("s-codex", "codex");
    }
  };

  @Test
  void addsTheHarnessTheAuditLogKnows() {
    Profile profile = store.getOrCreateProfile("p");
    store.insertMemory(profile.id(), Memory.of(MemoryType.FACT, "one", "s-codex", null, null));
    store.insertMemory(profile.id(), Memory.of(MemoryType.FACT, "two", "s-manual", null, null));

    List<SessionActivity> sessions = new SessionService(store, audit).list("p", null, 10);

    Map<String, SessionActivity> byId = Map.of(sessions.get(0).sessionId(), sessions.get(0), sessions.get(1).sessionId(), sessions.get(1));
    assertEquals("codex", byId.get("s-codex").harness());
    assertNull(byId.get("s-manual").harness());
  }

  @Test
  void rejectsUnknownProfilesAndBadLimits() {
    SessionService service = new SessionService(store, audit);
    assertThrows(NotFoundException.class, () -> service.list("ghost", null, 10));
    store.getOrCreateProfile("p");
    assertThrows(IllegalArgumentException.class, () -> service.list("p", null, 0));
  }
}
