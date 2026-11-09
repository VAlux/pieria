package dev.alvo.pieria.harvest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HarvestSecretStoreTests {

  @TempDir
  Path root;

  private HarvestPaths paths() {
    return new HarvestPaths(root.resolve("harvesters"), root.resolve("no-bin"));
  }

  private HarvestSecretStore store() {
    return new HarvestSecretStore(paths());
  }

  @Test
  void storesPerSource() {
    HarvestSecretStore store = store();

    store.put("a", "token", "one");
    store.put("b", "token", "two");

    assertThat(store.secrets("a")).containsExactlyEntriesOf(Map.of("token", "one"));
    assertThat(store().secrets("b")).containsExactlyEntriesOf(Map.of("token", "two"));
  }

  @Test
  void updateKeepsOtherNames() {
    HarvestSecretStore store = store();
    store.put("a", "token", "one");
    store.put("a", "refresh", "r");

    store.put("a", "token", "new");

    assertThat(store.secrets("a")).containsOnly(Map.entry("token", "new"), Map.entry("refresh", "r"));
  }

  @Test
  void removeDeletesASourcesSecrets() {
    HarvestSecretStore store = store();
    store.put("a", "token", "one");
    store.put("b", "token", "two");

    store.remove("a");

    assertThat(store.secrets("a")).isEmpty();
    assertThat(store.secrets("b")).isNotEmpty();
  }

  @Test
  void missingFileIsEmpty() {
    assertThat(store().secrets("a")).isEmpty();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void fileIsPrivate() throws Exception {
    store().put("a", "token", "one");

    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(paths().secretsFile()))).isEqualTo("rw-------");
  }

  @Test
  void aCorruptFileIsNotOverwritten() throws Exception {
    Files.createDirectories(paths().root());
    Files.writeString(paths().secretsFile(), "not json");

    assertThatThrownBy(() -> store().put("a", "token", "one")).isInstanceOf(IllegalStateException.class);

    assertThat(Files.readString(paths().secretsFile())).isEqualTo("not json");
  }

  @Test
  void survivesConcurrentWrites() throws Exception {
    HarvestSecretStore store = store();
    List<Thread> writers = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      String name = "name-" + i;
      writers.add(Thread.startVirtualThread(() -> store.put("a", name, "value")));
    }
    for (Thread writer : writers) {
      writer.join();
    }

    assertThat(store.secrets("a")).hasSize(20);
  }
}
