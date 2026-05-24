package dev.alvo.pieria.config;

import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.model.ModelGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FirstRunServiceTests {

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
    FirstRunService service = new FirstRunService(resolver,
      new FirstRunProperties(true, false, FirstRunProperties.ModelPullPolicy.NEVER),
      new StorageProperties("sqlite"), pieria, new NoopModelGateway());

    FirstRunService.SetupState first = service.initialize();
    FirstRunService.SetupState second = service.initialize();

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

  private static PieriaProperties properties(Path dbPath) {
    return new PieriaProperties(
      new PieriaProperties.Daemon("127.0.0.1", 8077),
      new PieriaProperties.Db(dbPath.toString()),
      new PieriaProperties.Ollama("http://localhost:11434"),
      new PieriaProperties.Model("small", "large", "embed", 1024),
      new PieriaProperties.Ingestion(10000, 2, 4, 9, 32, 5, false, 5000),
      new PieriaProperties.Retrieval(false, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 10, 3000));
  }

  private static class NoopModelGateway implements ModelGateway {
    @Override
    public List<Memory> extractMemories(List<Message> messages) {
      return List.of();
    }

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
