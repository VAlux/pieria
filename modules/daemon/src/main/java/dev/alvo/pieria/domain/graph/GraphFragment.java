package dev.alvo.pieria.domain.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The graph extracted from a single verified memory: a set of entity nodes and the
 * {@code (source, relation, target)} triples connecting them, carried <em>by name</em> rather than
 * by id. Edge ids depend on the owning memory's id, so they are computed inside the store
 * transaction (see {@code MemoryStore.store(profileId, memory, graph)}) once the memory id is
 * known; the fragment never carries ids.
 *
 * <p>Names and types are expected to be normalized deterministically (by {@code EntityNormalizer})
 * <em>before</em> the fragment is built, so that id computation is stable. An {@link #empty()}
 * fragment is a no-op.
 */
public record GraphFragment(List<Entity> entities, List<EdgeTriple> triples) {

  /**
   * A directed relationship between two entities, referenced by normalized name + type.
   */
  public record EdgeTriple(
    String sourceName,
    String sourceType,
    String relation,
    String targetName,
    String targetType) {
  }

  public GraphFragment {
    entities = entities == null ? List.of() : List.copyOf(entities);
    triples = triples == null ? List.of() : List.copyOf(triples);
  }

  private static final GraphFragment EMPTY = new GraphFragment(List.of(), List.of());

  public static GraphFragment empty() {
    return EMPTY;
  }

  public boolean isEmpty() {
    return entities.isEmpty() && triples.isEmpty();
  }

  /**
   * All distinct entity nodes referenced by this fragment: the explicit {@link #entities()} list
   * unioned with every triple endpoint, deduped by {@code (type, name)} with the first-seen
   * payload winning. Endpoints not in the explicit list are materialized with an empty payload so
   * that edges always reference a persisted node.
   */
  public List<Entity> allEntities() {
    Map<String, Entity> byKey = new LinkedHashMap<>();
    for (Entity entity : entities) {
      byKey.putIfAbsent(key(entity.type(), entity.name()), entity);
    }

    for (EdgeTriple triple : triples) {
      byKey.putIfAbsent(
        key(triple.sourceType(), triple.sourceName()),
        Entity.of(triple.sourceType(), triple.sourceName(), "{}"));

      byKey.putIfAbsent(
        key(triple.targetType(), triple.targetName()),
        Entity.of(triple.targetType(), triple.targetName(), "{}"));
    }

    return new ArrayList<>(byKey.values());
  }

  private static String key(String type, String name) {
    return (type == null ? "" : type) + "\u001f" + (name == null ? "" : name);
  }
}
