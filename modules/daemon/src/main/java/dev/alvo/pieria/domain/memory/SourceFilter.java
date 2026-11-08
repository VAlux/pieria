package dev.alvo.pieria.domain.memory;

import dev.alvo.pieria.api.response.MemoryOrigin;

import java.util.List;
import java.util.Set;

/**
 * Limits a recall by where memories came from. A non-empty {@code include} admits only those
 * sources, so it also leaves out the user's own memories; {@code exclude} hides sources and wins
 * over {@code include}. Ids are validated here because the store inlines them into SQL.
 */
public record SourceFilter(Set<String> include, Set<String> exclude) {

  public static final SourceFilter NONE = new SourceFilter(Set.of(), Set.of());

  public SourceFilter {
    include = Set.copyOf(include);
    exclude = Set.copyOf(exclude);
    include.forEach(SourceFilter::requireValid);
    exclude.forEach(SourceFilter::requireValid);
  }

  public static SourceFilter of(List<String> include, List<String> exclude) {
    return new SourceFilter(
      include == null ? Set.of() : Set.copyOf(include),
      exclude == null ? Set.of() : Set.copyOf(exclude));
  }

  public boolean isEmpty() {
    return include.isEmpty() && exclude.isEmpty();
  }

  /**
   * Whether a memory from {@code originSourceId} passes; null means it is not harvested.
   */
  public boolean admits(String originSourceId) {
    if (originSourceId != null && exclude.contains(originSourceId)) {
      return false;
    }
    return include.isEmpty() || (originSourceId != null && include.contains(originSourceId));
  }

  private static void requireValid(String id) {
    if (!id.matches(MemoryOrigin.SOURCE_ID_REGEX)) {
      throw new IllegalArgumentException("invalid source id: " + id);
    }
  }
}
