package dev.alvo.pieria.cli.modules.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import dev.alvo.pieria.cli.log.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TomlConfigMergerTests {

  private final TomlConfigMerger merger = new TomlConfigMerger();

  private Logger nullLog() {
    return new Logger();
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
    merger.save(file, root, false, nullLog());

    ObjectNode reloaded = merger.load(file);
    assertThat(reloaded.path("model").asString()).isEqualTo("gpt-5");
    assertThat(reloaded.path("mcp_servers").path("other").path("command").asString()).isEqualTo("x");
    assertThat(reloaded.path("mcp_servers").path("pieria").path("command").asString())
      .isEqualTo("/opt/pieria/bin/pieria-gateway");
  }

  /**
   * A Windows gateway path carries backslashes and spaces, and TOML basic strings treat {@code \} as
   * an escape. Jackson sidesteps that by emitting a <em>literal</em> string ({@code '...'}), which
   * has no escape processing at all — so the path is written verbatim and reads back unchanged. Pin
   * the round-trip: the failure mode is a harness that can no longer launch the gateway.
   */
  @Test
  void writesWindowsPathsAsLiteralStringsThatRoundTrip(@TempDir Path dir) throws IOException {
    String gateway = "C:\\Users\\First Last\\AppData\\Local\\Pieria\\bin\\pieria-gateway.exe";
    Path file = dir.resolve("config.toml");

    ObjectNode root = merger.newObject();
    ObjectNode pieria = merger.newObject();
    pieria.put("command", gateway);
    merger.childObject(root, "mcp_servers").set("pieria", pieria);
    merger.save(file, root, false, nullLog());

    assertThat(Files.readString(file)).contains("'" + gateway + "'");
    assertThat(merger.load(file).path("mcp_servers").path("pieria").path("command").asString())
      .isEqualTo(gateway);
  }

  /**
   * An apostrophe in a user name ({@code C:\Users\O'Brien\...}) cannot live in a TOML literal
   * string, so the writer has to switch quoting styles. Pin the round-trip rather than the encoding.
   */
  @Test
  void roundTripsPathsContainingAnApostrophe(@TempDir Path dir) throws IOException {
    String gateway = "C:\\Users\\O'Brien\\AppData\\Local\\Pieria\\bin\\pieria-gateway.exe";
    Path file = dir.resolve("config.toml");

    ObjectNode root = merger.newObject();
    ObjectNode pieria = merger.newObject();
    pieria.put("command", gateway);
    merger.childObject(root, "mcp_servers").set("pieria", pieria);
    merger.save(file, root, false, nullLog());

    assertThat(merger.load(file).path("mcp_servers").path("pieria").path("command").asString())
      .isEqualTo(gateway);
  }

  @Test
  void dryRunDoesNotWrite(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("config.toml");
    ObjectNode root = merger.newObject();
    root.put("x", 1);
    merger.save(file, root, true, nullLog());
    assertThat(Files.exists(file)).isFalse();
  }
}
