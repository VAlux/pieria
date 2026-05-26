package dev.alvo.pieria.cli.harness;

import dev.alvo.pieria.cli.modules.harness.TomlConfigMerger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TomlConfigMergerTests {

  private final TomlConfigMerger merger = new TomlConfigMerger();

  private PrintStream nullOut() {
    return new PrintStream(new ByteArrayOutputStream());
  }

  @Test
  void roundTripsAndPreservesUnrelatedTables(@TempDir Path dir) throws IOException {
    Path file = dir.resolve(".codex").resolve("config.toml");
    // Pre-existing unrelated config the user already has.
    Files.createDirectories(file.getParent());
    Files.writeString(file, "model = \"gpt-5\"\n\n[mcp_servers.other]\ncommand = \"x\"\n");

    ObjectNode root = merger.load(file);
    ObjectNode servers = merger.childObject(root, "mcp_servers");
    ObjectNode pieria = merger.newObject();
    pieria.put("command", "/opt/pieria/bin/pieria-gateway");
    servers.set("pieria", pieria);
    merger.save(file, root, false, nullOut());

    ObjectNode reloaded = merger.load(file);
    assertThat(reloaded.path("model").asString()).isEqualTo("gpt-5");
    assertThat(reloaded.path("mcp_servers").path("other").path("command").asString()).isEqualTo("x");
    assertThat(reloaded.path("mcp_servers").path("pieria").path("command").asString())
      .isEqualTo("/opt/pieria/bin/pieria-gateway");
  }

  @Test
  void dryRunDoesNotWrite(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("config.toml");
    ObjectNode root = merger.newObject();
    root.put("x", 1);
    merger.save(file, root, true, nullOut());
    assertThat(Files.exists(file)).isFalse();
  }
}
