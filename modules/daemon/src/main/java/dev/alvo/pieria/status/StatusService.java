package dev.alvo.pieria.status;

import dev.alvo.pieria.api.ApiVersion;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.StorageProperties;
import dev.alvo.pieria.daemon.DaemonSupervisor;
import dev.alvo.pieria.setup.BootstrapService;
import dev.alvo.pieria.setup.BootstrapService.SetupState;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.OptionalLong;

/**
 * Aggregates daemon readiness for {@code /pieria-status}: setup state (via {@link BootstrapService}),
 * the configured backend/model identifiers, whether vector search actually came up, and the
 * vectorization outbox backlog. The outbox probe
 * is fail-open — a backend that does not support it (or a transient store error) yields no backlog
 * figure rather than a 500, so status stays informational.
 */
@Service
public class StatusService {

  private final BootstrapService setupService;
  private final StorageProperties storage;
  private final PieriaProperties pieria;
  private final MemoryStore store;
  private final Environment environment;
  private final DaemonSupervisor supervisor;
  private final String daemonVersion;

  public StatusService(BootstrapService setupService,
                       StorageProperties storage,
                       PieriaProperties pieria,
                       MemoryStore store,
                       Environment environment,
                       DaemonSupervisor supervisor) {
    this.setupService = setupService;
    this.storage = storage;
    this.pieria = pieria;
    this.store = store;
    this.environment = environment;
    this.supervisor = supervisor;
    String version = StatusService.class.getPackage().getImplementationVersion();
    this.daemonVersion = version == null || version.isBlank() ? "dev" : version;
  }

  public StatusView status() {
    SetupState state = setupService.setupState();
    PieriaProperties.Model model = pieria.model();
    OptionalLong outboxDepth = outboxDepth();

    return new StatusView(
      state.directoriesReady() && state.databaseParentReady() ? "ready" : "initializing",
      state.paths().databaseFile().toString(),
      storage.backend(),
      store.isVectorSearchAvailable(),
      pieria.provider().name(),
      model.extractionModel(),
      model.synthesisModel(),
      model.embedding(),
      outboxDepth.isPresent() ? outboxDepth.getAsLong() : null,
      state,
      pieria.daemon().host(),
      boundPort(),
      pieria.provider().type(),
      ApiVersion.CURRENT,
      daemonVersion,
      supervisor.mechanism());
  }

  /** The port actually listened on; differs from the configured one when it was set to 0. */
  private int boundPort() {
    Integer bound = environment.getProperty("local.server.port", Integer.class);
    return bound != null && bound > 0 ? bound : pieria.daemon().port();
  }

  private OptionalLong outboxDepth() {
    try {
      return store.vectorizationOutboxDepth();
    } catch (RuntimeException e) {
      return OptionalLong.empty();
    }
  }

  public record StatusView(
    String status,
    String databaseFile,
    String backend,
    boolean vectorSearch,
    String provider,
    String extractionModel,
    String synthesisModel,
    String embedding,
    Long outboxDepth,
    SetupState setup,
    String host,
    int port,
    String providerType,
    int apiVersion,
    String daemonVersion,
    String restartMechanism) {
  }
}
