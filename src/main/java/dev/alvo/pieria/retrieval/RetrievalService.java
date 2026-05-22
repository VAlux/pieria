package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.NotFoundException;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Phase 1 read path (SPEC 7, phase doc step 7). Resolves the profile, gathers recall candidates
 * from the store, and asks the model to synthesize an answer. Shaped so Phase 3 can introduce
 * FTS/vector/HyDE channels + RRF fusion without changing the controller.
 */
@Service
public class RetrievalService {

  private final MemoryStore store;
  private final ModelGateway modelGateway;

  public RetrievalService(MemoryStore store, ModelGateway modelGateway) {
    this.store = store;
    this.modelGateway = modelGateway;
  }

  /**
   * Run retrieval for {@code query} within the named profile and synthesize an answer.
   *
   * @throws NotFoundException if the profile does not exist
   */
  public RecallResult recall(String profileName, String query, int limit) {
    var profile = store.findProfile(profileName).orElseThrow(() -> NotFoundException.profile(profileName));

    List<RecallCandidate> candidates = store.findRecallCandidates(profile.id(), query, limit);
    String answer = modelGateway.synthesizeRecall(query, candidates);

    List<Memory> memories = candidates.stream()
      .map(RecallCandidate::memory)
      .toList();

    return new RecallResult(answer, memories);
  }
}
