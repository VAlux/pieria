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

  @TempDir
  Path bin;

  private HarvesterRegistry registry() {
    return new HarvesterRegistry(new HarvestPaths(root, bin));
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
      json.append(json.length() > 1 ? "," : "").append('"').append(id).append("\":{\"entrypoint\":\"")
        .append(Hash.sha256Hex(root.resolve(id).resolve("run"))).append("\",\"manifest\":\"")
        .append(Hash.sha256Hex(root.resolve(id).resolve("harvester.toml"))).append("\"}");
    }
    Files.writeString(root.resolve("pins.json"), json.append('}').toString());
  }

  private static List<String> ids(List<RegisteredHarvester> harvesters) {
    return harvesters.stream().map(harvester -> harvester.manifest().id()).toList();
  }

  private void shipBinary() throws Exception {
    Files.writeString(bin.resolve("pieria-harvest"), "binary");
  }

  @Test
  void aBundledHarvesterIsListedWhenItsBinaryExists() throws Exception {
    shipBinary();

    RegisteredHarvester found = registry().find("html").orElseThrow();

    assertThat(ids(registry().list())).containsExactlyElementsOf(BundledHarvesters.IDS);
    assertThat(found.bundled()).isTrue();
    assertThat(found.command()).containsExactly(bin.resolve("pieria-harvest").toString(), "html");
  }

  @Test
  void aWindowsBinaryCounts() throws Exception {
    Files.writeString(bin.resolve("pieria-harvest.exe"), "binary");

    assertThat(registry().find("html")).isPresent();
  }

  @Test
  void aBundledHarvesterIsHiddenWithoutItsBinary() {
    assertThat(registry().find("html")).isEmpty();
    assertThat(registry().list()).isEmpty();
  }

  @Test
  void bundledNeedsNoPin() throws Exception {
    shipBinary();

    registry().requirePinned(registry().find("html").orElseThrow());
  }

  @Test
  void aBundledBinaryThatDisappearsFailsTheLaunchCheck() throws Exception {
    shipBinary();
    RegisteredHarvester found = registry().find("html").orElseThrow();
    Files.delete(bin.resolve("pieria-harvest"));

    assertThatThrownBy(() -> registry().requirePinned(found)).isInstanceOf(HarvesterIntegrityException.class);
  }

  @Test
  void bundledWorksBeforeTheRootExists() throws Exception {
    shipBinary();
    HarvesterRegistry registry = new HarvesterRegistry(new HarvestPaths(root.resolve("absent"), bin));

    assertThat(ids(registry.list())).containsExactlyElementsOf(BundledHarvesters.IDS);
    assertThat(registry.diagnose()).extracting(HarvesterDiagnosis::directory).containsExactlyElementsOf(BundledHarvesters.IDS);
  }

  @Test
  void bundledWinsOverAnInstalledDirectoryOfTheSameId() throws Exception {
    shipBinary();
    install("html", manifest("html"));
    pin("html");

    assertThat(registry().find("html").orElseThrow().bundled()).isTrue();
    assertThat(ids(registry().list())).containsExactlyElementsOf(BundledHarvesters.IDS);
    assertThat(registry().diagnose()).filteredOn(entry -> !entry.ok()).extracting(HarvesterDiagnosis::detail)
      .singleElement().asString().contains("bundled");
  }

  @Test
  void doctorReportsABundledHarvester() throws Exception {
    shipBinary();

    HarvesterDiagnosis entry = registry().diagnose().getFirst();

    assertThat(entry.directory()).isEqualTo("html");
    assertThat(entry.ok()).isTrue();
    assertThat(entry.detail()).isEqualTo("bundled");
  }

  @Test
  void doctorReportsAMissingBundledBinary() {
    HarvesterDiagnosis entry = registry().diagnose().getFirst();

    assertThat(entry.directory()).isEqualTo("html");
    assertThat(entry.ok()).isFalse();
    assertThat(entry.detail()).contains("missing");
  }

  @Test
  void listsAPinnedHarvester() throws Exception {
    Path dir = install("issues", manifest("issues"));
    pin("issues");

    RegisteredHarvester found = registry().find("issues").orElseThrow();

    assertThat(ids(registry().list())).containsExactly("issues");
    assertThat(found.directory()).isEqualTo(dir);
    assertThat(found.command()).containsExactly(dir.resolve("run").toString());
  }

  @Test
  void skipsAnUnpinnedHarvester() throws Exception {
    install("issues", manifest("issues"));

    assertThat(registry().list()).isEmpty();
    assertThat(registry().find("issues")).isEmpty();
  }

  @Test
  void skipsAChangedEntrypoint() throws Exception {
    Path dir = install("issues", manifest("issues"));
    pin("issues");

    Files.writeString(dir.resolve("run"), "#!/bin/sh\necho tampered\n");

    assertThat(registry().list()).isEmpty();
    assertThat(registry().find("issues")).isEmpty();
  }

  @Test
  void skipsAChangedManifest() throws Exception {
    Path dir = install("issues", manifest("issues"));
    pin("issues");

    Files.writeString(dir.resolve("harvester.toml"), manifest("issues") + "network = [\"evil.example\"]\n");

    assertThat(registry().list()).isEmpty();
  }

  @Test
  void requirePinnedRejectsAChangeAfterFind() throws Exception {
    Path dir = install("issues", manifest("issues"));
    pin("issues");
    RegisteredHarvester found = registry().find("issues").orElseThrow();

    Files.writeString(dir.resolve("run"), "#!/bin/sh\necho tampered\n");

    assertThatThrownBy(() -> registry().requirePinned(found)).isInstanceOf(HarvesterIntegrityException.class);
  }

  @Test
  void requirePinnedAcceptsAnUnchangedFile() throws Exception {
    install("issues", manifest("issues"));
    pin("issues");

    registry().requirePinned(registry().find("issues").orElseThrow());
  }

  @Test
  void skipsADirectoryWhoseNameDiffersFromItsId() throws Exception {
    install("issues", manifest("other"));
    pin("issues");

    assertThat(registry().list()).isEmpty();
  }

  @Test
  void oneBrokenManifestDoesNotHideOthers() throws Exception {
    install("broken", "not = = toml");
    install("issues", manifest("issues"));
    pin("issues");

    assertThat(ids(registry().list())).containsExactly("issues");
  }

  @Test
  void aMissingEntrypointIsSkipped() throws Exception {
    Path dir = install("issues", manifest("issues"));
    pin("issues");

    Files.delete(dir.resolve("run"));

    assertThat(registry().list()).isEmpty();
  }

  @Test
  void aTraversalIdFindsNothing() {
    assertThat(registry().find("../outside")).isEmpty();
  }

  private static String problem(List<HarvesterDiagnosis> diagnoses, String directory) {
    return diagnoses.stream().filter(entry -> entry.directory().equals(directory)).findFirst().orElseThrow().detail();
  }

  @Test
  void diagnoseReportsEachProblem() throws Exception {
    install("good", manifest("good"));
    install("changed", manifest("changed"));
    install("unpinned", manifest("unpinned"));
    install("broken", "not = = toml");
    install("mismatch", manifest("other"));
    pin("good", "changed");
    Files.writeString(root.resolve("changed/run"), "tampered");

    List<HarvesterDiagnosis> diagnoses = registry().diagnose();

    assertThat(diagnoses).filteredOn(HarvesterDiagnosis::ok).extracting(HarvesterDiagnosis::directory).containsExactly("good");
    assertThat(problem(diagnoses, "changed")).contains("checksum");
    assertThat(problem(diagnoses, "unpinned")).contains("pinned");
    assertThat(problem(diagnoses, "broken")).contains("manifest");
    assertThat(problem(diagnoses, "mismatch")).contains("other");
  }

  @Test
  void diagnoseReportsAMissingInterpreter() throws Exception {
    install("script", """
      id = "script"
      name = "Name"
      version = "1"
      protocol = 1
      command = ["no-such-interpreter-here", "./run"]
      entrypoint = "./run"
      """);
    pin("script");

    assertThat(problem(registry().diagnose(), "script")).contains("no-such-interpreter-here");
  }

  @Test
  void diagnoseSkipsWorkingDirectories() throws Exception {
    Files.createDirectories(root.resolve(".staging-1"));

    assertThat(registry().diagnose().stream().filter(entry -> !BundledHarvesters.isBundled(entry.directory())).toList()).isEmpty();
  }

  @Test
  void missingRootIsEmpty() {
    assertThat(new HarvesterRegistry(new HarvestPaths(root.resolve("absent"), bin)).list()).isEmpty();
  }

  @Test
  void seesNewHarvestersWithoutRestart() throws Exception {
    HarvesterRegistry registry = registry();
    assertThat(registry.list()).isEmpty();

    install("issues", manifest("issues"));
    pin("issues");

    assertThat(ids(registry.list())).containsExactly("issues");
  }
}
