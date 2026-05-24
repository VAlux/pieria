package dev.alvo.pieria.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class VecExtensionResolverTests {

  @Test
  void picksPlatformFileNamePerOs() {
    assertThat(VecExtensionResolver.platformExtensionFileName("Mac OS X")).isEqualTo("vec0.dylib");
    assertThat(VecExtensionResolver.platformExtensionFileName("Windows 11")).isEqualTo("vec0.dll");
    assertThat(VecExtensionResolver.platformExtensionFileName("Linux")).isEqualTo("vec0.so");
  }

  @Test
  void configuredPathWinsWhenFileExists(@TempDir Path dir) throws IOException {
    Path explicit = Files.createFile(dir.resolve("custom-vec0.dylib"));
    Files.createFile(dir.resolve("vec0.dylib"));

    Optional<Path> resolved = VecExtensionResolver.resolve(
      explicit.toString(), null, List.of(dir), "Mac OS X");

    // Explicit configured path wins over the conventional bundled vec0 in the same directory.
    assertThat(resolved).contains(explicit.toAbsolutePath().normalize());
  }

  @Test
  void fallsBackToEnvPathWhenConfiguredBlank(@TempDir Path dir) throws IOException {
    Path env = Files.createFile(dir.resolve("env-vec0.so"));

    Optional<Path> resolved = VecExtensionResolver.resolve(
      "  ", env.toString(), List.of(), "Linux");

    assertThat(resolved).contains(env.toAbsolutePath().normalize());
  }

  @Test
  void findsBundledExtensionBesideBinary(@TempDir Path dir) throws IOException {
    Path bundled = Files.createFile(dir.resolve("vec0.so"));

    Optional<Path> resolved = VecExtensionResolver.resolve(
      null, null, List.of(dir), "Linux");

    assertThat(resolved).contains(bundled.toAbsolutePath().normalize());
  }

  @Test
  void ignoresConfiguredPathThatDoesNotExistAndSearchesCandidates(@TempDir Path dir) throws IOException {
    Path bundled = Files.createFile(dir.resolve("vec0.dylib"));

    Optional<Path> resolved = VecExtensionResolver.resolve(
      dir.resolve("missing.dylib").toString(), null, List.of(dir), "Mac OS X");

    assertThat(resolved).contains(bundled.toAbsolutePath().normalize());
  }

  @Test
  void emptyWhenNothingResolves(@TempDir Path dir) {
    Optional<Path> resolved = VecExtensionResolver.resolve(
      null, null, List.of(dir), "Linux");

    assertThat(resolved).isEmpty();
  }

  @Test
  void embeddedResourcePathIsArchScopedUnderNative() {
    assertThat(VecExtensionResolver.embeddedResourcePath("Mac OS X", "aarch64"))
      .isEqualTo("native/macos-aarch64/vec0.dylib");
    assertThat(VecExtensionResolver.embeddedResourcePath("Mac OS X", "x86_64"))
      .isEqualTo("native/macos-x86_64/vec0.dylib");
    assertThat(VecExtensionResolver.embeddedResourcePath("Windows 11", "amd64"))
      .isEqualTo("native/windows-x86_64/vec0.dll");
    assertThat(VecExtensionResolver.embeddedResourcePath("Linux", "aarch64"))
      .isEqualTo("native/linux-aarch64/vec0.so");
  }

  @Test
  void platformKeyNormalizesArchAliases() {
    assertThat(VecExtensionResolver.platformKey("Linux", "amd64")).isEqualTo("linux-x86_64");
    assertThat(VecExtensionResolver.platformKey("Linux", "x86_64")).isEqualTo("linux-x86_64");
    assertThat(VecExtensionResolver.platformKey("Mac OS X", "arm64")).isEqualTo("macos-aarch64");
    assertThat(VecExtensionResolver.platformKey("Mac OS X", "aarch64")).isEqualTo("macos-aarch64");
  }

  @Test
  void extractWritesResourceAndCreatesParentDirs(@TempDir Path dir) throws IOException {
    Path target = dir.resolve("run").resolve("vec0.so");
    byte[] payload = "fake-extension".getBytes(StandardCharsets.UTF_8);

    Path written = VecExtensionResolver.extract(new ByteArrayInputStream(payload), target);

    assertThat(written).isEqualTo(target.toAbsolutePath().normalize());
    assertThat(Files.readAllBytes(target)).isEqualTo(payload);
  }

  @Test
  void extractSkipsRewriteWhenContentIsIdentical(@TempDir Path dir) throws IOException {
    Path target = dir.resolve("vec0.so");
    byte[] content = "same-content".getBytes(StandardCharsets.UTF_8);
    Files.write(target, content);
    Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(
      Files.getLastModifiedTime(target).toMillis() - 5_000));
    long staleModified = Files.getLastModifiedTime(target).toMillis();

    // Identical bytes (same SHA-256) ⇒ no rewrite.
    VecExtensionResolver.extract(new ByteArrayInputStream(content), target);

    assertThat(Files.getLastModifiedTime(target).toMillis()).isEqualTo(staleModified);
  }

  @Test
  void extractOverwritesWhenContentDiffers(@TempDir Path dir) throws IOException {
    Path target = dir.resolve("vec0.so");
    Files.write(target, "old-binary".getBytes(StandardCharsets.UTF_8));
    Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(
      Files.getLastModifiedTime(target).toMillis() - 5_000));
    long staleModified = Files.getLastModifiedTime(target).toMillis();

    // Different bytes even if same length ⇒ rewrite happens (upgrade case).
    VecExtensionResolver.extract(new ByteArrayInputStream("new-binary".getBytes(StandardCharsets.UTF_8)), target);

    assertThat(Files.getLastModifiedTime(target).toMillis()).isGreaterThan(staleModified);
    assertThat(new String(Files.readAllBytes(target), StandardCharsets.UTF_8)).isEqualTo("new-binary");
  }
}
