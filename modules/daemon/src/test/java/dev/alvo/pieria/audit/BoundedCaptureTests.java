package dev.alvo.pieria.audit;

import dev.alvo.pieria.tools.Hash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedCaptureTests {
  @Test
  void retainsPrefixButCountsAndHashesCompleteStream() {
    byte[] bytes = "abcdefghij".getBytes(StandardCharsets.UTF_8);
    BoundedCapture capture = new BoundedCapture(4);
    capture.accept(bytes, 0, 3);
    capture.accept(bytes, 3, 7);

    CapturedPayload result = capture.snapshot();
    assertThat(result.body()).isEqualTo("abcd");
    assertThat(result.bytes()).isEqualTo(10);
    assertThat(result.sha256()).isEqualTo(Hash.sha256Hex(bytes));
    assertThat(result.truncated()).isTrue();
  }

  @Test
  void exactLimitIsNotTruncated() {
    byte[] bytes = "four".getBytes(StandardCharsets.UTF_8);
    BoundedCapture capture = new BoundedCapture(4);
    capture.accept(bytes, 0, bytes.length);
    assertThat(capture.snapshot().truncated()).isFalse();
  }
}
