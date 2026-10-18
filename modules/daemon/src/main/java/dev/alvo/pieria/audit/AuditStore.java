package dev.alvo.pieria.audit;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence boundary for append-only profile audit events.
 */
public interface AuditStore {
  void append(AuditEvent event);

  List<AuditEvent> search(String profileName, AuditQuery query);

  Optional<AuditEvent> find(String profileName, String id);

  void deleteForProfile(String profileId, String profileName);

  /** The harness that most recently reported each of these sessions; sessions never seen are absent. */
  default Map<String, String> harnessBySession(String profileName, Collection<String> sessionIds) {
    return Map.of();
  }
}
