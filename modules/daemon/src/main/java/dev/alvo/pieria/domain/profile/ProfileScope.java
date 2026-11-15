package dev.alvo.pieria.domain.profile;

import java.util.List;
import java.util.Objects;

/**
 * The profiles one recall reads: the requested profile and every profile below it. The requested
 * profile comes first and is the one whose config, usage counters and audit trail the recall uses;
 * the others only contribute memories.
 *
 * @param primaryId the requested profile
 * @param ids       {@code primaryId} followed by its descendants, without repeats
 */
public record ProfileScope(String primaryId, List<String> ids) {

  public ProfileScope {
    Objects.requireNonNull(primaryId, "primaryId");
    ids = ids == null || ids.isEmpty() ? List.of(primaryId) : List.copyOf(ids);
    if (!ids.getFirst().equals(primaryId)) {
      throw new IllegalArgumentException("a profile scope must start with its primary profile");
    }
  }

  /** A scope of just this profile. */
  public static ProfileScope single(String profileId) {
    return new ProfileScope(profileId, List.of(profileId));
  }

  /** True when the scope holds only the requested profile, so it reads exactly as before. */
  public boolean isSingle() {
    return ids.size() == 1;
  }

  /** The profiles below the requested one. */
  public List<String> descendants() {
    return ids.subList(1, ids.size());
  }

  public boolean contains(String profileId) {
    return ids.contains(profileId);
  }
}
