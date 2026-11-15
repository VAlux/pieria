package dev.alvo.pieria.harvest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class BundledHarvestersTests {

  @Test
  void everyIdHasAManifestWithThatId() {
    BundledHarvesters.IDS.forEach(id -> assertThat(BundledHarvesters.manifest(id).id()).isEqualTo(id));
  }

  @Test
  void theIdListMatchesTheManifestsDirectory() throws Exception {
    try (Stream<Path> directories = Files.list(Path.of("../harvesters/manifests"))) {
      List<String> shipped = directories.map(path -> path.getFileName().toString()).sorted().toList();

      assertThat(BundledHarvesters.IDS).containsExactlyInAnyOrderElementsOf(shipped);
    }
  }

  @Test
  void isBundledMatchesTheList() {
    assertThat(BundledHarvesters.isBundled("html")).isTrue();
    assertThat(BundledHarvesters.isBundled("jira")).isTrue();
    assertThat(BundledHarvesters.isBundled("teams")).isTrue();
    assertThat(BundledHarvesters.isBundled("slack")).isFalse();
  }
}
