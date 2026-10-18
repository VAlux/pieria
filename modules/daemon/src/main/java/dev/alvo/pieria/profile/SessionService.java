package dev.alvo.pieria.profile;

import dev.alvo.pieria.audit.AuditStore;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.domain.memory.SessionActivity;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Lists a profile's sessions for the console's session filter. Memories and messages say when a
 * session was active; only the audit log knows which harness reported it, so the two are joined here.
 */
@Service
public class SessionService {

  private final MemoryStore store;
  private final AuditStore audit;

  public SessionService(MemoryStore store, AuditStore audit) {
    this.store = store;
    this.audit = audit;
  }

  public List<SessionActivity> list(String profileName, String prefix, int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("limit must be between 1 and 500");
    }
    String profileId = store.findProfile(profileName).orElseThrow(() -> NotFoundException.profile(profileName)).id();
    List<SessionActivity> sessions = store.listSessions(profileId, prefix, limit);
    if (sessions.isEmpty()) return sessions;
    Map<String, String> harnesses = audit.harnessBySession(profileName, sessions.stream().map(SessionActivity::sessionId).toList());
    return sessions.stream().map(s -> s.withHarness(harnesses.get(s.sessionId()))).toList();
  }
}
