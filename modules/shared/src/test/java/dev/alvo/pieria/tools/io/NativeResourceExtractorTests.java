package dev.alvo.pieria.tools.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeResourceExtractorTests {

  @Test
  void platformKeyNormalizesArchAliases() {
    assertThat(NativeResourceExtractor.platformKey("Linux", "amd64")).isEqualTo("linux-x86_64");
    assertThat(NativeResourceExtractor.platformKey("Linux", "x86_64")).isEqualTo("linux-x86_64");
    assertThat(NativeResourceExtractor.platformKey("Mac OS X", "arm64")).isEqualTo("macos-aarch64");
    assertThat(NativeResourceExtractor.platformKey("Mac OS X", "aarch64")).isEqualTo("macos-aarch64");
    assertThat(NativeResourceExtractor.platformKey("Windows 11", "amd64")).isEqualTo("windows-x86_64");
  }

  @Test
  void librarySuffixPicksPlatformExtension() {
    assertThat(NativeResourceExtractor.librarySuffix("Mac OS X")).isEqualTo("dylib");
    assertThat(NativeResourceExtractor.librarySuffix("Windows 11")).isEqualTo("dll");
    assertThat(NativeResourceExtractor.librarySuffix("Linux")).isEqualTo("so");
  }

  @Test
  void existingFileRequiresARegularFileOnDisk(@TempDir Path dir) throws IOException {
    Path file = Files.createFile(dir.resolve("lib.so"));

    assertThat(NativeResourceExtractor.existingFile(file.toString())).contains(file.toAbsolutePath().normalize());
    assertThat(NativeResourceExtractor.existingFile(null)).isEmpty();
    assertThat(NativeResourceExtractor.existingFile("  ")).isEmpty();
    assertThat(NativeResourceExtractor.existingFile(dir.resolve("missing.so").toString())).isEmpty();
  }

  @Test
  void extractWritesResourceAndCreatesParentDirs(@TempDir Path dir) throws IOException {
    Path target = dir.resolve("run").resolve("lib.so");
    byte[] payload = "fake-library".getBytes(StandardCharsets.UTF_8);

    Path written = NativeResourceExtractor.extract(new ByteArrayInputStream(payload), target);

    assertThat(written).isEqualTo(target.toAbsolutePath().normalize());
    assertThat(Files.readAllBytes(target)).isEqualTo(payload);
  }

  @Test
  void extractSkipsRewriteWhenContentIsIdentical(@TempDir Path dir) throws IOException {
    Path target = dir.resolve("lib.so");
    byte[] content = "same-content".getBytes(StandardCharsets.UTF_8);
    Files.write(target, content);
    Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(
      Files.getLastModifiedTime(target).toMillis() - 5_000));
    long staleModified = Files.getLastModifiedTime(target).toMillis();

    NativeResourceExtractor.extract(new ByteArrayInputStream(content), target);

    assertThat(Files.getLastModifiedTime(target).toMillis()).isEqualTo(staleModified);
  }

  @Test
  void sha256MatchesComparesFileContentToExpectedBytes(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("lib.so");
    Files.write(file, "content".getBytes(StandardCharsets.UTF_8));

    assertThat(NativeResourceExtractor.sha256Matches(file, "content".getBytes(StandardCharsets.UTF_8))).isTrue();
    assertThat(NativeResourceExtractor.sha256Matches(file, "other".getBytes(StandardCharsets.UTF_8))).isFalse();
  }
}
