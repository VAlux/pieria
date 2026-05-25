package dev.alvo.pieria.cli.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JsonConfigMergerTests {

  private final JsonConfigMerger merger = new JsonConfigMerger();

  private PrintStream nullOut() {
    return new PrintStream(new ByteArrayOutputStream());
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
    merger.save(file, root, false, nullOut());

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

  @Test
  void dryRunDoesNotWrite(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("d.json");
    ObjectNode root = merger.newObject();
    root.put("x", 1);
    merger.save(file, root, true, nullOut());
    assertThat(Files.exists(file)).isFalse();
  }
}
