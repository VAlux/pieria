package dev.alvo.pieria.tools.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileOpsTests {

  @TempDir
  Path dir;

  @Test
  void ensureParentDirectoryCreatesNestedMissingDirs() {
    Path target = dir.resolve("a/b/c/file.txt");

    FileOps.ensureParentDirectory(target);

    assertThat(target.getParent()).isDirectory();
  }

  @Test
  void ensureParentDirectoryIsNoOpForRootlessPath() {
    FileOps.ensureParentDirectory(Path.of("no-parent.txt"));
  }

  @Test
  void writeFileStringCreatesParentAndWritesContent() throws IOException {
    Path target = dir.resolve("nested/config.txt");

    FileOps.writeFile(target, "hello");

    assertThat(Files.readString(target)).isEqualTo("hello");
  }

  @Test
  void writeFileBytesCreatesParentAndWritesContent() throws IOException {
    Path target = dir.resolve("nested/data.bin");

    FileOps.writeFile(target, new byte[] {1, 2, 3});

    assertThat(Files.readAllBytes(target)).containsExactly(1, 2, 3);
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void makeExecutableSetsExecuteBits() throws IOException {
    Path file = Files.createFile(dir.resolve("script.sh"));

    FileOps.makeExecutable(file);

    assertThat(Files.getPosixFilePermissions(file)).contains(
      PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE,
      PosixFilePermission.OTHERS_EXECUTE);
  }

  @Test
  void readTextQuietlyReturnsContentForExistingFile() throws IOException {
    Path file = dir.resolve("doc.txt");
    Files.writeString(file, "content");

    assertThat(FileOps.readTextQuietly(file)).isEqualTo("content");
  }

  @Test
  void readTextQuietlyReturnsNullForMissingFile() {
    assertThat(FileOps.readTextQuietly(dir.resolve("missing.txt"))).isNull();
  }
}
