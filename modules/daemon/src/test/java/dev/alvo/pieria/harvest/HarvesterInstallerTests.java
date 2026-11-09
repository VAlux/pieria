package dev.alvo.pieria.harvest;

import dev.alvo.pieria.domain.error.ConflictException;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.tools.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HarvesterInstallerTests {

  private static final String MANIFEST = """
    id = "jira"
    name = "Jira"
    version = "1"
    protocol = 1
    command = ["./run"]
    """;

  @TempDir
  Path temp;

  private Path root;
  private HarvestPaths paths;
  private HarvestStore store;
  private HarvesterInstaller installer;

  @BeforeEach
  void setUp() {
    root = temp.resolve("harvesters");
    paths = new HarvestPaths(root);
    store = mock(HarvestStore.class);
    installer = new HarvesterInstaller(paths, store);
  }

  private Path packageDir(String name, String runContent) throws Exception {
    Path dir = Files.createDirectories(temp.resolve(name));
    Files.writeString(dir.resolve("harvester.toml"), MANIFEST);
    Files.writeString(dir.resolve("run"), runContent);
    return dir;
  }

  private Path zip(String name, Entry... entries) throws Exception {
    Path file = temp.resolve(name);
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
      for (Entry entry : entries) {
        out.putNextEntry(new ZipEntry(entry.name()));
        out.write(entry.content().getBytes());
        out.closeEntry();
      }
    }
    return file;
  }

  private record Entry(String name, String content) {
  }

  private List<String> rootEntries() throws Exception {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(root)) {
      return entries.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }

  private JsonNode pin(String id) {
    return new HarvestPins(paths).read().path(id);
  }

  private HarvesterRegistry registry() {
    return new HarvesterRegistry(paths);
  }

  @Test
  void installsADirectoryAndPinsIt() throws Exception {
    Path source = packageDir("pkg", "#!/bin/sh\n");

    HarvesterPackage installed = installer.install(source, null, null, false);

    assertThat(registry().find("jira")).isPresent();
    assertThat(pin("jira").path("entrypoint").asString()).isEqualTo(Hash.sha256Hex(source.resolve("run")));
    assertThat(pin("jira").path("manifest").asString()).isEqualTo(Hash.sha256Hex(source.resolve("harvester.toml")));
    assertThat(installed.entrypointSha256()).isEqualTo(pin("jira").path("entrypoint").asString());
    assertThat(rootEntries()).containsExactly("jira", "pins.json");
  }

  @Test
  void installsAZip() throws Exception {
    installer.install(zip("p.zip", new Entry("harvester.toml", MANIFEST), new Entry("run", "x")), null, null, false);

    assertThat(registry().find("jira")).isPresent();
  }

  @Test
  void installsAZipWithOneTopLevelDirectory() throws Exception {
    installer.install(zip("p.zip", new Entry("pkg/harvester.toml", MANIFEST), new Entry("pkg/run", "x")), null, null, false);

    assertThat(registry().find("jira")).isPresent();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void zipEntrypointBecomesExecutable() throws Exception {
    installer.install(zip("p.zip", new Entry("harvester.toml", MANIFEST), new Entry("run", "x")), null, null, false);

    assertThat(root.resolve("jira/run").toFile().canExecute()).isTrue();
  }

  @Test
  void inspectLeavesNothingBehind() throws Exception {
    HarvesterPackage inspected = installer.inspect(packageDir("pkg", "x"));

    assertThat(inspected.manifest().id()).isEqualTo("jira");
    assertThat(inspected.entrypointSha256()).hasSize(64);
    assertThat(rootEntries()).isEmpty();
  }

  @Test
  void rejectsARelativePath() {
    assertThatThrownBy(() -> installer.inspect(Path.of("relative/pkg"))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAFileThatIsNotAZip() throws Exception {
    Path file = Files.writeString(temp.resolve("package.tar"), "x");

    assertThatThrownBy(() -> installer.inspect(file)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsACorruptZip() throws Exception {
    Path file = Files.writeString(temp.resolve("package.zip"), "this is not a zip");

    assertThatThrownBy(() -> installer.inspect(file)).isInstanceOf(IllegalArgumentException.class);
    assertThat(rootEntries()).isEmpty();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void rejectsASymlink() throws Exception {
    Path source = packageDir("pkg", "x");
    Files.createSymbolicLink(source.resolve("link"), Path.of("/etc/hosts"));

    assertThatThrownBy(() -> installer.install(source, null, null, false)).isInstanceOf(IllegalArgumentException.class);
    assertThat(rootEntries()).isEmpty();
  }

  @Test
  void rejectsZipSlip() throws Exception {
    Path file = zip("p.zip", new Entry("harvester.toml", MANIFEST), new Entry("run", "x"), new Entry("../../escaped", "evil"));

    assertThatThrownBy(() -> installer.install(file, null, null, false)).isInstanceOf(IllegalArgumentException.class);
    assertThat(temp.resolve("escaped")).doesNotExist();
    assertThat(rootEntries()).isEmpty();
  }

  @Test
  void rejectsAnAbsoluteZipEntry() throws Exception {
    Path file = zip("p.zip", new Entry("/etc/evil", "x"));

    assertThatThrownBy(() -> installer.install(file, null, null, false)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAZipBomb() throws Exception {
    Path file = temp.resolve("bomb.zip");
    try (OutputStream raw = Files.newOutputStream(file); ZipOutputStream out = new ZipOutputStream(raw)) {
      out.putNextEntry(new ZipEntry("big"));
      byte[] block = new byte[1024 * 1024];
      for (int i = 0; i < 260; i++) {
        out.write(block);
      }
      out.closeEntry();
    }

    assertThatThrownBy(() -> installer.install(file, null, null, false)).isInstanceOf(IllegalArgumentException.class);
    assertThat(rootEntries()).isEmpty();
  }

  @Test
  void rejectsTooManyFiles() throws Exception {
    Path file = temp.resolve("many.zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
      for (int i = 0; i <= 20_000; i++) {
        out.putNextEntry(new ZipEntry("f" + i));
        out.closeEntry();
      }
    }

    assertThatThrownBy(() -> installer.install(file, null, null, false)).isInstanceOf(IllegalArgumentException.class);
    assertThat(rootEntries()).isEmpty();
  }

  @Test
  void rejectsATruncatedZip() throws Exception {
    byte[] noise = new byte[64 * 1024];
    new java.util.Random(7).nextBytes(noise);
    Path whole = temp.resolve("whole.zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(whole))) {
      out.putNextEntry(new ZipEntry("harvester.toml"));
      out.write(noise);
      out.closeEntry();
    }
    byte[] bytes = Files.readAllBytes(whole);
    Path truncated = Files.write(temp.resolve("truncated.zip"), java.util.Arrays.copyOf(bytes, bytes.length / 2));

    assertThatThrownBy(() -> installer.inspect(truncated)).isInstanceOf(IllegalArgumentException.class);
    assertThat(rootEntries()).isEmpty();
  }

  @Test
  void rejectsZipEntriesThatCollide() throws Exception {
    Path file = zip("p.zip", new Entry("a", "x"), new Entry("a/b", "y"));

    assertThatThrownBy(() -> installer.inspect(file)).isInstanceOf(IllegalArgumentException.class);
    assertThat(rootEntries()).isEmpty();
  }

  @Test
  void rejectsAFileEntryNamedForTheRoot() throws Exception {
    Path file = zip("p.zip", new Entry(".", "x"));

    assertThatThrownBy(() -> installer.inspect(file)).isInstanceOf(IllegalArgumentException.class);
    assertThat(rootEntries()).isEmpty();
  }

  @Test
  void installsAZipThatFinderAddedMetadataTo() throws Exception {
    installer.install(zip("p.zip", new Entry("pkg/harvester.toml", MANIFEST), new Entry("pkg/run", "x"),
      new Entry("__MACOSX/pkg/._run", "resource fork")), null, null, false);

    assertThat(registry().find("jira")).isPresent();
    assertThat(root.resolve("jira/run")).exists();
  }

  @Test
  void aReplaceThatCannotPinRestoresTheOldHarvester() throws Exception {
    installer.install(packageDir("pkg", "one"), null, null, false);
    Files.delete(paths.pinsFile());
    Files.createDirectories(paths.pinsFile().resolve("in-the-way"));

    assertThatThrownBy(() -> installer.install(packageDir("pkg2", "two"), null, null, true)).isInstanceOf(RuntimeException.class);

    assertThat(Files.readString(root.resolve("jira/run"))).isEqualTo("one");
    assertThat(rootEntries()).containsExactly("jira", "pins.json");
  }

  @Test
  void rejectsAMissingManifest() throws Exception {
    Path source = Files.createDirectories(temp.resolve("empty"));

    assertThatThrownBy(() -> installer.install(source, null, null, false)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAnInvalidManifest() throws Exception {
    Path source = packageDir("pkg", "x");
    Files.writeString(source.resolve("harvester.toml"), "not = = toml");

    assertThatThrownBy(() -> installer.install(source, null, null, false)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAMissingEntrypoint() throws Exception {
    Path source = packageDir("pkg", "x");
    Files.delete(source.resolve("run"));

    assertThatThrownBy(() -> installer.install(source, null, null, false)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aChangedPackageFailsExpectedHashes() throws Exception {
    Path source = packageDir("pkg", "x");
    HarvesterPackage inspected = installer.inspect(source);
    Files.writeString(source.resolve("run"), "changed since it was shown");

    assertThatThrownBy(() -> installer.install(source, inspected.entrypointSha256(), inspected.manifestSha256(), false))
      .isInstanceOf(ConflictException.class);
    assertThat(registry().find("jira")).isEmpty();
  }

  @Test
  void refusesToOverwriteWithoutReplace() throws Exception {
    installer.install(packageDir("pkg", "one"), null, null, false);

    assertThatThrownBy(() -> installer.install(packageDir("pkg2", "two"), null, null, false))
      .isInstanceOf(ConflictException.class);
    assertThat(Files.readString(root.resolve("jira/run"))).isEqualTo("one");
  }

  @Test
  void replaceSwapsTheHarvesterAndPin() throws Exception {
    installer.install(packageDir("pkg", "one"), null, null, false);

    installer.install(packageDir("pkg2", "two"), null, null, true);

    assertThat(Files.readString(root.resolve("jira/run"))).isEqualTo("two");
    assertThat(registry().find("jira")).isPresent();
    assertThat(rootEntries()).containsExactly("jira", "pins.json");
  }

  @Test
  void aFailedReplaceKeepsTheOldHarvester() throws Exception {
    installer.install(packageDir("pkg", "one"), null, null, false);
    String pinBefore = pin("jira").toString();
    Path bad = packageDir("bad", "two");
    Files.writeString(bad.resolve("harvester.toml"), "not = = toml");

    assertThatThrownBy(() -> installer.install(bad, null, null, true)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> installer.install(packageDir("pkg3", "three"), "wrong", "wrong", true)).isInstanceOf(ConflictException.class);

    assertThat(Files.readString(root.resolve("jira/run"))).isEqualTo("one");
    assertThat(pin("jira").toString()).isEqualTo(pinBefore);
    assertThat(registry().find("jira")).isPresent();
  }

  @Test
  void failedInstallLeavesNoStaging() throws Exception {
    Path source = packageDir("pkg", "x");
    Files.delete(source.resolve("run"));

    assertThatThrownBy(() -> installer.install(source, null, null, false)).isInstanceOf(IllegalArgumentException.class);

    assertThat(rootEntries()).isEmpty();
  }

  @Test
  void removeDeletesDirectoryAndPin() throws Exception {
    installer.install(packageDir("pkg", "x"), null, null, false);

    installer.remove("jira");

    assertThat(root.resolve("jira")).doesNotExist();
    assertThat(pin("jira").isMissingNode()).isTrue();
  }

  @Test
  void removeRefusesAHarvesterInUse() throws Exception {
    installer.install(packageDir("pkg", "x"), null, null, false);
    when(store.anySourceUses("jira")).thenReturn(true);

    assertThatThrownBy(() -> installer.remove("jira")).isInstanceOf(ConflictException.class);
    assertThat(root.resolve("jira")).exists();
  }

  @Test
  void removingAnUnknownHarvesterIsNotFound() {
    assertThatThrownBy(() -> installer.remove("absent")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void removingATraversalIdIsNotFound() {
    assertThatThrownBy(() -> installer.remove("../outside")).isInstanceOf(NotFoundException.class);
  }
}
