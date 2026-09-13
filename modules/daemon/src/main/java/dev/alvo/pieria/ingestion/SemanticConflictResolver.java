package dev.alvo.pieria.ingestion;

import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.temporal.ConflictDecision;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.storage.MemoryStore;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** A bounded, conservative proposal stage. It never runs while a storage transaction is held. */
final class SemanticConflictResolver {
  private SemanticConflictResolver() {}

  static Memory check(MemoryStore store, ModelGateway model, String profile, Memory incoming) {
    return check(store, model, profile, incoming, null);
  }

  static Memory check(MemoryStore store, ModelGateway model, String profile, Memory incoming, GraphFragment graph) {
    if ((incoming.type() != MemoryType.FACT && incoming.type() != MemoryType.INSTRUCTION)
        || (incoming.topicKey() != null && incoming.topicKey().startsWith("code:"))
        || incoming.payload().contains("\"temporal_unresolved\":true")) return incoming.withConflict(null);
    var candidates = graph == null ? store.conflictCandidates(profile, incoming, 12) : store.conflictCandidates(profile, incoming, graph, 12);
    if (candidates.isEmpty()) return incoming.withConflict(null);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var pending = executor.submit(() -> model.classifyConflicts(incoming.content(), candidates));
    try {
      List<ConflictDecision> decisions = pending.get(3, TimeUnit.SECONDS);
      if (decisions == null) return incoming.withConflict(null);
      var accepted = decisions.stream().filter(d -> d != null &&
        ("replacement".equals(d.relationship()) || "correction".equals(d.relationship()))
        && grounded(incoming.content(), d.incomingEvidence()) && grounded(incoming.content(), d.subject())
        && grounded(incoming.content(), d.property())
        && candidates.stream().anyMatch(c -> c.revisionId().equals(d.revisionId())
          && grounded(c.content(), d.existingEvidence()) && grounded(c.content(), d.subject())
          && grounded(c.content(), d.property()))).toList();
      // Multiple possible properties/targets are ambiguous, so keep every assertion independent.
      return accepted.size() == 1 ? incoming.withConflict(accepted.getFirst()) : incoming.withConflict(null);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return incoming.withConflict(null);
    } finally {
      pending.cancel(true);
      executor.shutdownNow();
    }
  }

  private static boolean grounded(String source, String quote) {
    return quote != null && !quote.isBlank() && source.toLowerCase(Locale.ROOT).contains(quote.toLowerCase(Locale.ROOT));
  }
}
