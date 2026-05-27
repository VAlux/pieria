package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.StatusResponse;
import dev.alvo.pieria.config.FirstRunService;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.StorageProperties;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.OptionalLong;

/**
 * Local status endpoint for first-run and operational checks. It deliberately omits provider URLs,
 * secrets, and row-level memory data.
 */
@RestController
@RequestMapping("/pieria-status")
public class StatusController {

  private final FirstRunService firstRunService;
  private final StorageProperties storage;
  private final PieriaProperties pieria;
  private final MemoryStore store;

  public StatusController(FirstRunService firstRunService,
                          StorageProperties storage,
                          PieriaProperties pieria,
                          MemoryStore store) {
    this.firstRunService = firstRunService;
    this.storage = storage;
    this.pieria = pieria;
    this.store = store;
  }

  @GetMapping
  public StatusResponse status() {
    FirstRunService.SetupState state = firstRunService.setupState();
    PieriaProperties.Model model = pieria.model();
    OptionalLong outboxDepth = outboxDepth();

    return new StatusResponse(
      state.directoriesReady() && state.databaseParentReady() ? "ready" : "initializing",
      state.paths().databaseFile().toString(),
      storage.backend(),
      "ollama",
      model.chatSmall(),
      model.chatLarge(),
      model.embedding(),
      outboxDepth.isPresent() ? outboxDepth.getAsLong() : null,
      new StatusResponse.Setup(
        state.enabled(),
        state.directoriesReady(),
        state.databaseParentReady(),
        state.modelStatus(),
        state.modelPullPolicy(),
        state.paths().configDir().toString(),
        state.paths().logsDir().toString(),
        state.paths().runtimeDir().toString()));
  }

  private OptionalLong outboxDepth() {
    try {
      return store.vectorizationOutboxDepth();
    } catch (RuntimeException e) {
      return OptionalLong.empty();
    }
  }
}
