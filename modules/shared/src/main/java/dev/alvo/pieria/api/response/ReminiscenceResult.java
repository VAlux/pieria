package dev.alvo.pieria.api.response;

/**
 * Terminal result of an orphan-adoption run, carried as the task result and rendered by the CLI as
 * the "done" line.
 *
 * @param memoriesScanned edgeless memories examined (all now stamped {@code graph_adopted_at})
 * @param memoriesAdopted  of those, how many yielded at least one entity/edge and joined the graph
 * @param entitiesAdded    total entities extracted across the run (including pre-existing upserts)
 * @param edgesAdded       total edges (triples) extracted across the run
 */
public record ReminiscenceResult(int memoriesScanned, int memoriesAdopted, int entitiesAdded, int edgesAdded) {
}
