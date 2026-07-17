package dev.alvo.pieria.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HashTests {

  @Test
  void hash128Is32HexCharsAndDeterministic() {
    String a = Hash.hash128("session", "user", "content");
    String b = Hash.hash128("session", "user", "content");

    assertThat(a).hasSize(32).isEqualTo(b);
  }

  @Test
  void sha256HexOfEmptyByteArrayMatchesKnownVector() {
    // SHA-256("") — well-known test vector.
    assertThat(Hash.sha256Hex(new byte[0]))
      .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  void sha256HexOfKnownStringMatchesKnownVector() {
    // SHA-256("abc") — well-known test vector.
    assertThat(Hash.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
      .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void sha256HexPathOverloadMatchesByteArrayOverload(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("data.bin");
    byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
    Files.write(file, content);

    assertThat(Hash.sha256Hex(file)).isEqualTo(Hash.sha256Hex(content));
  }
}
