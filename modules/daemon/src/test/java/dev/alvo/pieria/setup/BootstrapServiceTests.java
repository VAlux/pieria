package dev.alvo.pieria.setup;

import dev.alvo.pieria.config.VerifyMode;

import dev.alvo.pieria.config.AppDataPathResolver;
import dev.alvo.pieria.config.AppDataProperties;
import dev.alvo.pieria.config.FirstRunProperties;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.StorageProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.model.ModelGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapServiceTests {

  @TempDir
  Path tempDir;

  @Test
  void initializeCreatesConfiguredDirectoriesIdempotently() {
    Path root = tempDir.resolve("pieria");
    PieriaProperties pieria = properties(root.resolve("db").resolve("pieria.db"));
    AppDataPathResolver resolver = new AppDataPathResolver(
      new AppDataProperties(root.toString(), root.resolve("db").toString(),
        root.resolve("config").toString(), root.resolve("logs").toString(),
        root.resolve("run").toString()),
      pieria);
    BootstrapService service = new BootstrapService(resolver,
      new FirstRunProperties(true, false, FirstRunProperties.ModelPullPolicy.NEVER),
      new StorageProperties("sqlite"), pieria, new NoopModelGateway());

    BootstrapService.SetupState first = service.initialize();
    BootstrapService.SetupState second = service.initialize();

    assertThat(first.directoriesReady()).isTrue();
    assertThat(first.databaseParentReady()).isTrue();
    assertThat(second.directoriesReady()).isTrue();
    assertThat(second.databaseParentReady()).isTrue();
    assertThat(Files.isDirectory(root.resolve("db"))).isTrue();
    assertThat(Files.isDirectory(root.resolve("config"))).isTrue();
    assertThat(Files.isDirectory(root.resolve("logs"))).isTrue();
    assertThat(Files.isDirectory(root.resolve("run"))).isTrue();
    assertThat(second.paths().databaseFile()).isEqualTo(root.resolve("db").resolve("pieria.db").toAbsolutePath());
  }

  @Test
  void initializeWritesDefaultConfigWhenAbsentAndPreservesUserEdits() throws Exception {
    Path root = tempDir.resolve("pieria");
    Path configDir = root.resolve("config");
    PieriaProperties pieria = properties(root.resolve("db").resolve("pieria.db"));
    AppDataPathResolver resolver = new AppDataPathResolver(
      new AppDataProperties(root.toString(), root.resolve("db").toString(),
        configDir.toString(), root.resolve("logs").toString(),
        root.resolve("run").toString()),
      pieria);
    BootstrapService service = new BootstrapService(resolver,
      new FirstRunProperties(true, false, FirstRunProperties.ModelPullPolicy.NEVER),
      new StorageProperties("sqlite"), pieria, new NoopModelGateway());

    service.initialize();

    Path configFile = configDir.resolve("pieria.properties");
    assertThat(configFile).exists();
    assertThat(Files.readString(configFile)).contains("pieria.provider.base-url");

    // A second run must not clobber user edits.
    Files.writeString(configFile, "pieria.provider.name=edited\n");
    service.initialize();
    assertThat(Files.readString(configFile)).isEqualTo("pieria.provider.name=edited\n");
  }

  @Test
  void initializeSkipsDefaultConfigWhenFirstRunDisabled() {
    Path root = tempDir.resolve("pieria");
    Path configDir = root.resolve("config");
    PieriaProperties pieria = properties(root.resolve("db").resolve("pieria.db"));
    AppDataPathResolver resolver = new AppDataPathResolver(
      new AppDataProperties(root.toString(), root.resolve("db").toString(),
        configDir.toString(), root.resolve("logs").toString(),
        root.resolve("run").toString()),
      pieria);
    BootstrapService service = new BootstrapService(resolver,
      new FirstRunProperties(false, false, FirstRunProperties.ModelPullPolicy.NEVER),
      new StorageProperties("sqlite"), pieria, new NoopModelGateway());

    service.initialize();

    assertThat(configDir.resolve("pieria.properties")).doesNotExist();
  }

  @Test
  void setupStateReportsSkippedModelStatusWhenChecksDisabled() {
    Path root = tempDir.resolve("pieria");
    PieriaProperties pieria = properties(root.resolve("db").resolve("pieria.db"));
    AppDataPathResolver resolver = new AppDataPathResolver(
      new AppDataProperties(root.toString(), root.resolve("db").toString(),
        root.resolve("config").toString(), root.resolve("logs").toString(),
        root.resolve("run").toString()),
      pieria);
    BootstrapService service = new BootstrapService(resolver,
      new FirstRunProperties(true, false, FirstRunProperties.ModelPullPolicy.NEVER),
      new StorageProperties("sqlite"), pieria, new NoopModelGateway());

    service.initialize();

    assertThat(service.setupState().modelStatus()).isEqualTo("skipped");
  }

  private static PieriaProperties properties(Path dbPath) {
    return new PieriaProperties(
      new PieriaProperties.Daemon("127.0.0.1", 8077),
      new PieriaProperties.Db(dbPath.toString()),
      new PieriaProperties.Provider("http://localhost:11434", "test-key", "test-provider", "openai", "2024-10-21"),
      new PieriaProperties.Model("small", "large", "embed", 1024, 4, null, null),
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS, 1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70),
      new PieriaProperties.Retrieval(false, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 0.0, 0.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60, 0.78),
      null);
  }

  private static class NoopModelGateway implements ModelGateway {
    @Override
    public String synthesizeRecall(String query, List<RecallCandidate> candidates) {
      return "";
    }

    @Override
    public float[] embed(String text) {
      return new float[0];
    }
  }
}
