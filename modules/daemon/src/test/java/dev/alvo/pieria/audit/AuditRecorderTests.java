package dev.alvo.pieria.audit;

import dev.alvo.pieria.config.AuditProperties;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.tools.Hash;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditRecorderTests {
  @Test
  void persistenceFailureDoesNotEscapeIntoProfileCall() {
    AuditStore store = mock(AuditStore.class);
    doThrow(new IllegalStateException("disk full")).when(store).append(any());
    MemoryStore memories = mock(MemoryStore.class);
    when(memories.findProfile("alpha")).thenReturn(Optional.empty());
    AuditRecorder recorder = new AuditRecorder(store, memories, JsonMapper.builder().build(),
      new AuditProperties(1024));
    CapturedPayload empty = new CapturedPayload("", 0, Hash.sha256Hex(new byte[0]), false);

    assertDoesNotThrow(() -> recorder.recordHttp("alpha", "recall", "r1",
      new AuditCaller("api", null, "http", null, "127.0.0.1"), "POST",
      "/v1/profiles/alpha/recall", null, "application/json", "application/json",
      Instant.now(), Instant.now(), 200, empty, empty, null, null));
  }
}
