package dev.alvo.pieria.harvester.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedLineReaderTests {

  private static final class CountingEndlessStream extends InputStream {
    private long read;

    @Override
    public int read() {
      read++;
      return 'a';
    }

    long bytesRead() {
      return read;
    }
  }

  private static BoundedLineReader reader(String text) {
    return new BoundedLineReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), 1024);
  }

  @Test
  void readsLinesThenNullAtEof() {
    BoundedLineReader reader = reader("a\nb\n");

    assertThat(reader.readLine()).isEqualTo("a");
    assertThat(reader.readLine()).isEqualTo("b");
    assertThat(reader.readLine()).isNull();
  }

  @Test
  void stripsOneTrailingCarriageReturn() {
    assertThat(reader("a\r\n").readLine()).isEqualTo("a");
  }

  @Test
  void truncatedFinalLineIsAViolation() {
    BoundedLineReader reader = reader("a\nb");

    assertThat(reader.readLine()).isEqualTo("a");
    assertThatThrownBy(reader::readLine).isInstanceOf(ProtocolViolation.class).hasMessageContaining("truncated");
  }

  @Test
  void invalidUtf8IsAViolation() {
    byte[] bad = {'a', (byte) 0xC3, (byte) 0x28, '\n'};

    assertThatThrownBy(() -> new BoundedLineReader(new ByteArrayInputStream(bad), 1024).readLine())
      .isInstanceOf(ProtocolViolation.class);
  }

  @Test
  void lineAtTheLimitIsAccepted() {
    assertThat(reader("x".repeat(1024) + "\n").readLine()).hasSize(1024);
  }

  @Test
  void lineOverTheLimitIsAViolation() {
    assertThatThrownBy(() -> reader("x".repeat(1025) + "\n").readLine()).isInstanceOf(ProtocolViolation.class);
  }

  @Test
  void oversizeLineStopsReadingAtTheLimit() {
    CountingEndlessStream in = new CountingEndlessStream();

    assertThatThrownBy(() -> new BoundedLineReader(in, 1024).readLine()).isInstanceOf(ProtocolViolation.class);
    assertThat(in.bytesRead()).isLessThan(64 * 1024);
  }
}
