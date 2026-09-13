package dev.alvo.pieria.ingestion;

import dev.alvo.pieria.api.response.RevisionResponse;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.temporal.ConflictDecision;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.storage.MemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticConflictResolverTests {
  private final Memory incoming = Memory.of(MemoryType.FACT, "Alice uses editor Vim", "s", "editor.new", "{}");
  private final RevisionResponse candidate = new RevisionResponse("a", "r", "m", "Alice uses editor Emacs",
    Instant.EPOCH, null, Instant.EPOCH, Instant.EPOCH, null, null, "turn-1", false, false);
  private Memory check(String relationship, String subject) {
    MemoryStore store = mock(MemoryStore.class);
    when(store.conflictCandidates("p", incoming, 12)).thenReturn(List.of(candidate));
    ModelGateway model = mock(ModelGateway.class);
    when(model.classifyConflicts(incoming.content(), List.of(candidate))).thenReturn(List.of(new ConflictDecision(
      "r", relationship, subject, "editor", "editor Vim", "editor Emacs")));
    return SemanticConflictResolver.check(store, model, "p", incoming);
  }

  @Test void groundedReplacementAcrossKeysIsProposed() { assertNotNull(check("replacement", "Alice").conflict()); }
  @Test void compatibleMultivaluedUncertainAndDifferentSubjectsAreRetained() {
    assertNull(check("compatible", "Alice").conflict());
    assertNull(check("uncertain", "Alice").conflict());
    assertNull(check("equivalent", "Alice").conflict());
    assertNull(check("replacement", "Bob").conflict());
  }
  @Test void modelFailureDoesNotFailWrite() {
    MemoryStore store = mock(MemoryStore.class);
    when(store.conflictCandidates("p", incoming, 12)).thenReturn(List.of(candidate));
    ModelGateway model = mock(ModelGateway.class);
    when(model.classifyConflicts(anyString(), anyList())).thenThrow(new IllegalArgumentException("malformed"));
    assertNull(SemanticConflictResolver.check(store, model, "p", incoming).conflict());
  }
}
