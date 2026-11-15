package dev.alvo.pieria.storage;

import dev.alvo.pieria.domain.profile.ProfileScope;

import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Widens a recall's profile predicates from one profile to its subtree. A recall binds its
 * {@link ProfileScope} around the channel and fusion work, the way it binds the temporal snapshot and
 * the source filter, and the SQLite stores render each {@code profile_id = ?} through
 * {@link #matches}. Unbound, or bound to a profile without children, the predicate is exactly
 * {@code column = ?}, so a single-profile recall runs the same SQL as before.
 *
 * <p>Only a query for the scope's own primary profile is widened. Any other profile id passed while
 * a scope is bound keeps its plain predicate, so per-memory work that uses the memory's owning
 * profile is never widened by accident.
 */
public final class ProfileScopeSql {

  private static final ScopedValue<ProfileScope> SCOPE = ScopedValue.newInstance();

  private ProfileScopeSql() {
  }

  /** Runs {@code action} with {@code scope} bound for the stores on this thread. */
  public static <T> T within(ProfileScope scope, Supplier<T> action) {
    return ScopedValue.where(SCOPE, scope).call(action::get);
  }

  /** The bound scope when it widens {@code profileId}, else {@code null}. */
  public static ProfileScope widening(String profileId) {
    if (!SCOPE.isBound()) {
      return null;
    }
    ProfileScope scope = SCOPE.get();
    return !scope.isSingle() && scope.primaryId().equals(profileId) ? scope : null;
  }

  /**
   * {@code column = ?}, or, when the bound scope widens {@code profileId}, that predicate OR'ed with
   * the descendants' ids. The {@code ?} stays, so callers bind {@code profileId} exactly as before.
   * Profile ids are store-generated UUIDs; they are quoted defensively all the same.
   */
  public static String matches(String column, String profileId) {
    ProfileScope scope = widening(profileId);
    if (scope == null) {
      return column + " = ?";
    }
    String descendants = scope.descendants().stream()
      .map(id -> "'" + id.replace("'", "''") + "'")
      .collect(Collectors.joining(","));
    return "(" + column + " = ? OR " + column + " IN (" + descendants + "))";
  }
}
