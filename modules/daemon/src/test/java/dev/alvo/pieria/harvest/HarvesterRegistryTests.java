package dev.alvo.pieria.harvest;

import dev.alvo.pieria.tools.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HarvesterRegistryTests {

  @TempDir
  Path root;

  private HarvesterRegistry registry() {
    return new HarvesterRegistry(new HarvestPaths(root));
  }

  private static String manifest(String id) {
    return """
      id = "%s"
      name = "Name"
      version = "1"
      protocol = 1
      command = ["./run"]
      """.formatted(id);
  }

  private Path install(String directory, String manifest) throws Exception {
    Path dir = Files.createDirectories(root.resolve(directory));
    Files.writeString(dir.resolve("harvester.toml"), manifest);
    Files.writeString(dir.resolve("run"), "#!/bin/sh\n");
    return dir;
  }

  private void pin(String... ids) throws Exception {
    StringBuilder json = new StringBuilder("{");
    for (String id : ids) {
      json.append(json.length() > 1 ? "," : "").append('"').append(id).append("\":\"")
        .append(Hash.sha256Hex(root.resolve(id).resolve("run"))).append('"');
    }
    Files.writeString(root.resolve("pins.json"), json.append('}').toString());
  }

  private static List<String> ids(List<RegisteredHarvester> harvesters) {
    return harvesters.stream().map(harvester -> harvester.manifest().id()).toList();
  }

  @Test
  void listsAPinnedHarvester() throws Exception {
    Path dir = install("jira", manifest("jira"));
    pin("jira");

    RegisteredHarvester found = registry().find("jira").orElseThrow();

    assertThat(ids(registry().list())).containsExactly("jira");
    assertThat(found.directory()).isEqualTo(dir);
    assertThat(found.command()).containsExactly(dir.resolve("run").toString());
  }

  @Test
  void skipsAnUnpinnedHarvester() throws Exception {
    install("jira", manifest("jira"));

    assertThat(registry().list()).isEmpty();
    assertThat(registry().find("jira")).isEmpty();
  }

  @Test
  void skipsAChangedEntrypoint() throws Exception {
    Path dir = install("jira", manifest("jira"));
    pin("jira");

    Files.writeString(dir.resolve("run"), "#!/bin/sh\necho tampered\n");

    assertThat(registry().list()).isEmpty();
    assertThat(registry().find("jira")).isEmpty();
  }

  @Test
  void requirePinnedRejectsAChangeAfterFind() throws Exception {
    Path dir = install("jira", manifest("jira"));
    pin("jira");
    RegisteredHarvester found = registry().find("jira").orElseThrow();

    Files.writeString(dir.resolve("run"), "#!/bin/sh\necho tampered\n");

    assertThatThrownBy(() -> registry().requirePinned(found)).isInstanceOf(HarvesterIntegrityException.class);
  }

  @Test
  void requirePinnedAcceptsAnUnchangedFile() throws Exception {
    install("jira", manifest("jira"));
    pin("jira");

    registry().requirePinned(registry().find("jira").orElseThrow());
  }

  @Test
  void skipsADirectoryWhoseNameDiffersFromItsId() throws Exception {
    install("jira", manifest("other"));
    pin("jira");

    assertThat(registry().list()).isEmpty();
  }

  @Test
  void oneBrokenManifestDoesNotHideOthers() throws Exception {
    install("broken", "not = = toml");
    install("jira", manifest("jira"));
    pin("jira");

    assertThat(ids(registry().list())).containsExactly("jira");
  }

  @Test
  void aMissingEntrypointIsSkipped() throws Exception {
    Path dir = install("jira", manifest("jira"));
    pin("jira");

    Files.delete(dir.resolve("run"));

    assertThat(registry().list()).isEmpty();
  }

  @Test
  void aTraversalIdFindsNothing() {
    assertThat(registry().find("../outside")).isEmpty();
  }

  @Test
  void missingRootIsEmpty() {
    assertThat(new HarvesterRegistry(new HarvestPaths(root.resolve("absent"))).list()).isEmpty();
  }

  @Test
  void seesNewHarvestersWithoutRestart() throws Exception {
    HarvesterRegistry registry = registry();
    assertThat(registry.list()).isEmpty();

    install("jira", manifest("jira"));
    pin("jira");

    assertThat(ids(registry.list())).containsExactly("jira");
  }
}
