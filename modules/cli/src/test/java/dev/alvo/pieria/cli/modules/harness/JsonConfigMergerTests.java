package dev.alvo.pieria.cli.modules.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.alvo.pieria.cli.log.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JsonConfigMergerTests {

  private final JsonConfigMerger merger = new JsonConfigMerger();

  private Logger nullLog() {
    return new Logger();
  }

  @Test
  void loadsEmptyObjectWhenFileAbsent(@TempDir Path dir) throws IOException {
    ObjectNode root = merger.load(dir.resolve("missing.json"));
    assertThat(root.isEmpty()).isTrue();
  }

  @Test
  void preservesUnrelatedKeysAcrossSaveAndReload(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("sub").resolve("config.json");
    ObjectNode root = merger.load(file);
    root.put("untouched", 42);
    merger.childObject(root, "mcpServers").put("pieria", "x");
    merger.save(file, root, false, nullLog());

    ObjectNode reloaded = merger.load(file);
    assertThat(reloaded.path("untouched").asInt()).isEqualTo(42);
    assertThat(reloaded.path("mcpServers").path("pieria").asString()).isEqualTo("x");
  }

  @Test
  void childObjectAndArrayAreGetOrCreate(@TempDir Path dir) throws IOException {
    ObjectNode root = merger.load(dir.resolve("c.json"));
    ObjectNode a = merger.childObject(root, "a");
    a.put("k", 1);
    assertThat(merger.childObject(root, "a")).isSameAs(a);

    ArrayNode arr = merger.childArray(root, "list");
    arr.add("one");
    assertThat(merger.childArray(root, "list").size()).isEqualTo(1);
  }

  /**
   * The gateway command written into {@code .mcp.json} on Windows is a backslashed path, usually
   * with a space in it. JSON requires {@code \\}; pin the round-trip, since a mangled path means a
   * harness that silently cannot launch the gateway.
   */
  @Test
  void escapesWindowsPathsThroughAFullWriteReadCycle(@TempDir Path dir) throws IOException {
    String gateway = "C:\\Users\\First Last\\AppData\\Local\\Pieria\\bin\\pieria-gateway.exe";
    Path file = dir.resolve(".mcp.json");

    ObjectNode root = merger.newObject();
    merger.childObject(merger.childObject(root, "mcpServers"), "pieria").put("command", gateway);
    merger.save(file, root, false, nullLog());

    assertThat(Files.readString(file)).contains("\\\\Users\\\\First Last\\\\");
    assertThat(merger.load(file).path("mcpServers").path("pieria").path("command").asString())
      .isEqualTo(gateway);
  }

  @Test
  void dryRunDoesNotWrite(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("d.json");
    ObjectNode root = merger.newObject();
    root.put("x", 1);
    merger.save(file, root, true, nullLog());
    assertThat(Files.exists(file)).isFalse();
  }
}
