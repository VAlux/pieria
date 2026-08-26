package dev.alvo.pieria.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PropertiesFileEditorTests {

  @TempDir
  Path dir;

  private Path write(String... lines) throws IOException {
    Path file = dir.resolve("pieria.properties");
    Files.write(file, List.of(lines));
    return file;
  }

  @Test
  void readsAnExistingValue() throws IOException {
    Path file = write("# a comment", "pieria.daemon.port=8077");

    assertThat(PropertiesFileEditor.read(file).get("pieria.daemon.port")).contains("8077");
  }

  @Test
  void missingFileReadsAsEmpty() {
    PropertiesFileEditor editor = PropertiesFileEditor.read(dir.resolve("absent.properties"));

    assertThat(editor.get("pieria.daemon.port")).isEmpty();
  }

  @Test
  void commentedOutKeysAreNotValues() throws IOException {
    Path file = write("# pieria.daemon.port=9999", "!pieria.db.path=/nope");
    PropertiesFileEditor editor = PropertiesFileEditor.read(file);

    assertThat(editor.get("pieria.daemon.port")).isEmpty();
    assertThat(editor.get("pieria.db.path")).isEmpty();
  }

  @Test
  void setReplacesInPlaceAndKeepsEveryOtherLine() throws IOException {
    Path file = write(
      "# Pieria configuration",
      "# Edit and restart.",
      "",
      "pieria.daemon.port=8077",
      "pieria.provider.name=ollama");

    PropertiesFileEditor editor = PropertiesFileEditor.read(file);
    editor.set("pieria.daemon.port", "9090");
    editor.write(file);

    assertThat(Files.readAllLines(file)).containsExactly(
      "# Pieria configuration",
      "# Edit and restart.",
      "",
      "pieria.daemon.port=9090",
      "pieria.provider.name=ollama");
  }

  @Test
  void setAppendsUnderAManagedHeaderWhenTheKeyIsAbsent() throws IOException {
    Path file = write("# Pieria configuration", "pieria.daemon.port=8077");

    PropertiesFileEditor editor = PropertiesFileEditor.read(file);
    editor.set("pieria.provider.name", "lmstudio");
    editor.set("pieria.reminiscence.parallelism", "16");
    editor.write(file);

    List<String> lines = Files.readAllLines(file);
    assertThat(lines).containsSubsequence(
      "# Pieria configuration",
      "pieria.daemon.port=8077",
      PropertiesFileEditor.MANAGED_HEADER,
      "pieria.provider.name=lmstudio",
      "pieria.reminiscence.parallelism=16");
    assertThat(lines.stream().filter(PropertiesFileEditor.MANAGED_HEADER::equals)).hasSize(1);
  }

  @Test
  void removeDropsTheLineSoTheShippedDefaultTakesOverAgain() throws IOException {
    Path file = write("pieria.daemon.port=9090", "pieria.provider.name=ollama");

    PropertiesFileEditor editor = PropertiesFileEditor.read(file);
    editor.remove("pieria.daemon.port");
    editor.write(file);

    assertThat(Files.readAllLines(file)).containsExactly("pieria.provider.name=ollama");
  }

  @Test
  void handlesColonSeparatorsAndSurroundingWhitespace() throws IOException {
    Path file = write("  pieria.daemon.port : 8077  ");

    PropertiesFileEditor editor = PropertiesFileEditor.read(file);
    assertThat(editor.get("pieria.daemon.port")).contains("8077");

    editor.set("pieria.daemon.port", "9090");
    editor.write(file);
    assertThat(Files.readAllLines(file)).containsExactly("pieria.daemon.port=9090");
  }

  @Test
  void writeCreatesTheFileAndItsParentWhenAbsent() throws IOException {
    Path file = dir.resolve("nested").resolve("pieria.properties");

    PropertiesFileEditor editor = PropertiesFileEditor.read(file);
    editor.set("pieria.daemon.port", "8077");
    editor.write(file);

    assertThat(Files.readAllLines(file)).contains("pieria.daemon.port=8077");
  }

  @Test
  void getReportsTheValueThePropertiesParserWouldResolve() throws IOException {
    Path file = write("pieria.daemon.port=1111", "pieria.daemon.port=2222");

    assertThat(PropertiesFileEditor.read(file).get("pieria.daemon.port")).contains("2222");
  }

  @Test
  void setCollapsesDuplicateAssignmentsSoTheDaemonLoadsTheNewValue() throws IOException {
    Path file = write("pieria.daemon.port=1111", "# keep me", "pieria.daemon.port=2222");

    PropertiesFileEditor editor = PropertiesFileEditor.read(file);
    editor.set("pieria.daemon.port", "9090");
    editor.write(file);

    assertThat(Files.readAllLines(file)).containsExactly("pieria.daemon.port=9090", "# keep me");
  }

  @Test
  void removeDropsEveryAssignmentNotJustTheFirst() throws IOException {
    Path file = write("pieria.daemon.port=1111", "pieria.provider.name=ollama", "pieria.daemon.port=2222");

    PropertiesFileEditor editor = PropertiesFileEditor.read(file);
    editor.remove("pieria.daemon.port");
    editor.write(file);

    assertThat(Files.readAllLines(file)).containsExactly("pieria.provider.name=ollama");
    assertThat(PropertiesFileEditor.read(file).get("pieria.daemon.port")).isEmpty();
  }

  @Test
  void aKeyThatIsAPrefixOfAnotherKeyIsNotConfusedWithIt() throws IOException {
    Path file = write("pieria.db.path=/one", "pieria.db.path.backup=/two");

    PropertiesFileEditor editor = PropertiesFileEditor.read(file);
    assertThat(editor.get("pieria.db.path")).contains("/one");
    assertThat(editor.get("pieria.db.path.backup")).contains("/two");

    editor.set("pieria.db.path", "/three");
    editor.write(file);

    assertThat(Files.readAllLines(file)).containsExactly("pieria.db.path=/three", "pieria.db.path.backup=/two");
  }
}
