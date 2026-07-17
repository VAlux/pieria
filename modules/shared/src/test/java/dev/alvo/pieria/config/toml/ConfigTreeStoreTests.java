package dev.alvo.pieria.config.toml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ConfigTreeStoreTests {

  private final ConfigTreeStore store = new ConfigTreeStore(JsonMapper.builder().build());

  @Test
  void loadReturnsEmptyObjectWhenFileIsAbsent(@TempDir Path dir) throws IOException {
    ObjectNode root = store.load(dir.resolve("missing.json"));
    assertThat(root.isEmpty()).isTrue();
  }

  @Test
  void loadReturnsEmptyObjectWhenFileIsEmpty(@TempDir Path dir) throws IOException {
    Path file = Files.createFile(dir.resolve("empty.json"));
    ObjectNode root = store.load(file);
    assertThat(root.isEmpty()).isTrue();
  }

  @Test
  void loadParsesExistingObjectContent(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("config.json");
    Files.writeString(file, "{\"key\":\"value\"}");

    ObjectNode root = store.load(file);

    assertThat(root.path("key").asString()).isEqualTo("value");
  }

  @Test
  void childObjectIsGetOrCreate() {
    ObjectNode root = store.newObject();
    ObjectNode created = store.childObject(root, "a");
    created.put("k", 1);

    assertThat(store.childObject(root, "a")).isSameAs(created);
  }

  @Test
  void childArrayIsGetOrCreate() {
    ObjectNode root = store.newObject();
    ArrayNode created = store.childArray(root, "list");
    created.add("one");

    assertThat(store.childArray(root, "list")).isSameAs(created);
  }

  @Test
  void serializeRoundTripsThroughLoad(@TempDir Path dir) throws IOException {
    ObjectNode root = store.newObject();
    root.put("x", 1);
    Path file = dir.resolve("out.json");
    Files.writeString(file, store.serialize(root));

    assertThat(store.load(file).path("x").asInt()).isEqualTo(1);
  }
}
