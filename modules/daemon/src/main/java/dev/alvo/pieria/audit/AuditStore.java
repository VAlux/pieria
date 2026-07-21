package dev.alvo.pieria.audit;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for append-only profile audit events. */
public interface AuditStore {
  void append(AuditEvent event);
  List<AuditEvent> search(String profileName, AuditQuery query);
  Optional<AuditEvent> find(String profileName, String id);
  void deleteForProfile(String profileId, String profileName);
}
