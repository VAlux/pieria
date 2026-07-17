package dev.alvo.pieria.status;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.StorageProperties;
import dev.alvo.pieria.setup.BootstrapService;
import dev.alvo.pieria.setup.BootstrapService.SetupState;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.stereotype.Service;

import java.util.OptionalLong;

/**
 * Aggregates daemon readiness for {@code /pieria-status}: setup state (via {@link BootstrapService}),
 * the configured backend/model identifiers, and the vectorization outbox backlog. The outbox probe
 * is fail-open — a backend that does not support it (or a transient store error) yields no backlog
 * figure rather than a 500, so status stays informational.
 */
@Service
public class StatusService {

  private final BootstrapService setupService;
  private final StorageProperties storage;
  private final PieriaProperties pieria;
  private final MemoryStore store;

  public StatusService(BootstrapService setupService,
                       StorageProperties storage,
                       PieriaProperties pieria,
                       MemoryStore store) {
    this.setupService = setupService;
    this.storage = storage;
    this.pieria = pieria;
    this.store = store;
  }

  public record StatusView(
    String status,
    String databaseFile,
    String backend,
    String provider,
    String extractionModel,
    String synthesisModel,
    String embedding,
    Long outboxDepth,
    SetupState setup) {
  }

  public StatusView status() {
    SetupState state = setupService.setupState();
    PieriaProperties.Model model = pieria.model();
    OptionalLong outboxDepth = outboxDepth();

    return new StatusView(
      state.directoriesReady() && state.databaseParentReady() ? "ready" : "initializing",
      state.paths().databaseFile().toString(),
      storage.backend(),
      pieria.provider().name(),
      model.extractionModel(),
      model.synthesisModel(),
      model.embedding(),
      outboxDepth.isPresent() ? outboxDepth.getAsLong() : null,
      state);
  }

  private OptionalLong outboxDepth() {
    try {
      return store.vectorizationOutboxDepth();
    } catch (RuntimeException e) {
      return OptionalLong.empty();
    }
  }
}
