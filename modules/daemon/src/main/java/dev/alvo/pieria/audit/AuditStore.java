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

  /** {@code profileName} null searches every profile. */
  List<AuditEvent> search(String profileName, AuditQuery query);

  /** {@code profileName} null finds the event in any profile. */
  Optional<AuditEvent> find(String profileName, String id);

  /**
   * Distinct values with counts per dimension under the query's filters (cursor and limit ignored),
   * most frequent first. {@code profileName} null spans every profile.
   */
  default Map<String, List<Facet>> facets(String profileName, AuditQuery query) {
    return Map.of();
  }

  record Facet(String value, long count) {
  }

  void deleteForProfile(String profileId, String profileName);

  /** The harness that most recently reported each of these sessions; sessions never seen are absent. */
  default Map<String, String> harnessBySession(String profileName, Collection<String> sessionIds) {
    return Map.of();
  }
}
