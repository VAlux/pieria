package dev.alvo.pieria.tools.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipArchiveTests {

  @TempDir
  Path temp;

  @Test
  void extractsNestedEntriesPreservingLayout() throws IOException {
    Path zip = zip(temp.resolve("dist.zip"), entry("bin/pieria.exe", "cli"), entry("bin/version.txt", "v1.2.3"));
    Path dest = temp.resolve("out");

    ZipArchive.extract(zip, dest);

    assertThat(dest.resolve("bin/pieria.exe")).hasContent("cli");
    assertThat(dest.resolve("bin/version.txt")).hasContent("v1.2.3");
  }

  @Test
  void createsExplicitDirectoryEntries() throws IOException {
    Path zip = temp.resolve("dirs.zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      out.putNextEntry(new ZipEntry("bin/"));
      out.closeEntry();
    }

    ZipArchive.extract(zip, temp.resolve("out"));

    assertThat(temp.resolve("out/bin")).isDirectory();
  }

  @Test
  void overwritesAnExistingFile() throws IOException {
    Path dest = temp.resolve("out");
    Files.createDirectories(dest.resolve("bin"));
    Files.writeString(dest.resolve("bin/pieria.exe"), "old");

    ZipArchive.extract(zip(temp.resolve("d.zip"), entry("bin/pieria.exe", "new")), dest);

    assertThat(dest.resolve("bin/pieria.exe")).hasContent("new");
  }

  /** Zip slip: a `..` entry must not be able to write outside the destination. */
  @Test
  void rejectsEntriesEscapingTheDestination() throws IOException {
    Path zip = zip(temp.resolve("evil.zip"), entry("../pwned.txt", "boom"));
    Path dest = temp.resolve("out");

    assertThatThrownBy(() -> ZipArchive.extract(zip, dest))
      .isInstanceOf(IOException.class)
      .hasMessageContaining("escapes the destination");

    assertThat(temp.resolve("pwned.txt")).doesNotExist();
  }

  private record Entry(String name, String content) {
  }

  private static Entry entry(String name, String content) {
    return new Entry(name, content);
  }

  private static Path zip(Path target, Entry... entries) throws IOException {
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
      for (Entry entry : entries) {
        out.putNextEntry(new ZipEntry(entry.name()));
        write(out, entry.content());
        out.closeEntry();
      }
    }
    return target;
  }

  private static void write(OutputStream out, String content) throws IOException {
    out.write(content.getBytes(StandardCharsets.UTF_8));
  }
}
