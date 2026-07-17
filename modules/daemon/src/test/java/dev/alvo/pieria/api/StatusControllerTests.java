package dev.alvo.pieria.api;

import dev.alvo.pieria.config.VerifyMode;

import dev.alvo.pieria.api.controller.StatusController;
import dev.alvo.pieria.config.AppDataPathResolver;
import dev.alvo.pieria.config.AppDataProperties;
import dev.alvo.pieria.config.FirstRunProperties;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.StorageProperties;
import dev.alvo.pieria.setup.BootstrapService;
import dev.alvo.pieria.status.StatusService;
import dev.alvo.pieria.storage.MemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = StatusController.class)
@Import(StatusControllerTests.Wiring.class)
class StatusControllerTests {

  @Autowired
  MockMvc mvc;

  @Test
  void statusReportsLocalConfigurationAndOutboxDepth() throws Exception {
    mvc.perform(get("/pieria-status"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status", is("ready")))
      .andExpect(jsonPath("$.databasePath", endsWith("pieria.db")))
      .andExpect(jsonPath("$.backend", is("sqlite")))
      .andExpect(jsonPath("$.modelProvider", is("test-provider")))
      .andExpect(jsonPath("$.extractionModel", is("small")))
      .andExpect(jsonPath("$.synthesisModel", is("large")))
      .andExpect(jsonPath("$.embeddingModel", is("embed")))
      .andExpect(jsonPath("$.vectorizationOutboxDepth", is(0)))
      .andExpect(jsonPath("$.setup.directoriesReady", is(true)))
      .andExpect(jsonPath("$.setup.configDir", notNullValue()))
      .andExpect(jsonPath("$.setup.logsDir", notNullValue()))
      .andExpect(jsonPath("$.setup.runtimeDir", notNullValue()));
  }

  @TestConfiguration
  static class Wiring {

    private final Path root = createTempRoot();
    private final PieriaProperties pieria = new PieriaProperties(
      new PieriaProperties.Daemon("127.0.0.1", 8077),
      new PieriaProperties.Db(root.resolve("db").resolve("pieria.db").toString()),
      new PieriaProperties.Provider("http://localhost:11434", "test-key", "test-provider", "openai", "2024-10-21"),
      new PieriaProperties.Model("small", "large", "embed", 1024, 4, null, null),
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS, 1, 0, 0, false, 32, 5, false, 5000),
      new PieriaProperties.Retrieval(false, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 0.0, 0.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED),
      null);

    @Bean
    PieriaProperties pieriaProperties() {
      return pieria;
    }

    @Bean
    StorageProperties storageProperties() {
      return new StorageProperties("sqlite");
    }

    @Bean
    MemoryStore memoryStore() {
      return new StubMemoryStore();
    }

    @Bean
    BootstrapService localSetupService() {
      AppDataPathResolver resolver = new AppDataPathResolver(
        new AppDataProperties(root.toString(), root.resolve("db").toString(),
          root.resolve("config").toString(), root.resolve("logs").toString(),
          root.resolve("run").toString()),
        pieria);
      BootstrapService service = new BootstrapService(resolver,
        new FirstRunProperties(true, false, FirstRunProperties.ModelPullPolicy.NEVER),
        new StorageProperties("sqlite"), pieria, new StubModelGateway());
      service.initialize();
      return service;
    }

    @Bean
    StatusService statusService(BootstrapService setupService, StorageProperties storage,
                                PieriaProperties pieria, MemoryStore store) {
      return new StatusService(setupService, storage, pieria, store);
    }

    private static Path createTempRoot() {
      try {
        return Files.createTempDirectory("pieria-status-test");
      } catch (IOException e) {
        throw new IllegalStateException("failed to create temp directory", e);
      }
    }
  }
}
