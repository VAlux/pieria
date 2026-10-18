package dev.alvo.pieria.cluster;

import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How strongly clusters overlap, measured by the entities their member memories mention. The graph
 * view draws these as links between cluster hulls. An entity mentioned by most of the requested
 * clusters (say, the project itself) would link everything to everything, so one found in more than
 * {@code max(2, clusters / 2)} of them is left out; an entity two clusters share always counts.
 */
@Service
public class ClusterLinkService {

  public static final int MAX_CLUSTERS = 100;

  private final MemoryStore store;

  public ClusterLinkService(MemoryStore store) {
    this.store = store;
  }

  /** Two clusters sharing {@code shared} entities; {@code examples} names up to three of them. */
  public record Link(String a, String b, int shared, List<String> examples) {
  }

  public List<Link> links(String profileName, List<String> clusterIds, int minShared, int limit) {
    if (clusterIds.isEmpty() || clusterIds.size() > MAX_CLUSTERS) {
      throw new IllegalArgumentException("ids must name 1.." + MAX_CLUSTERS + " clusters");
    }
    if (minShared < 1 || limit < 1) {
      throw new IllegalArgumentException("minShared and limit must be positive");
    }
    String profileId = store.findProfile(profileName).orElseThrow(() -> NotFoundException.profile(profileName)).id();
    Map<String, Map<String, String>> entities = store.clusterEntities(profileId, Set.copyOf(clusterIds));

    Map<String, Long> reach = entities.values().stream().flatMap(m -> m.keySet().stream())
      .collect(Collectors.groupingBy(id -> id, Collectors.counting()));
    int clusters = entities.size();
    Map<String, String> names = new HashMap<>();
    entities.values().forEach(names::putAll);

    List<String> ids = entities.keySet().stream().sorted().toList();
    List<Link> links = new ArrayList<>();
    for (int i = 0; i < ids.size(); i++) {
      for (int j = i + 1; j < ids.size(); j++) {
        Set<String> right = entities.get(ids.get(j)).keySet();
        List<String> shared = entities.get(ids.get(i)).keySet().stream()
          .filter(right::contains)
          .filter(id -> reach.get(id) <= Math.max(2, clusters / 2))
          .sorted()
          .toList();
        if (shared.size() >= minShared) {
          links.add(new Link(ids.get(i), ids.get(j), shared.size(),
            shared.stream().map(names::get).sorted().limit(3).toList()));
        }
      }
    }
    return links.stream()
      .sorted(Comparator.comparingInt(Link::shared).reversed().thenComparing(Link::a).thenComparing(Link::b))
      .limit(limit)
      .toList();
  }
}
