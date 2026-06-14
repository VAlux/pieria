package dev.alvo.pieria.cli.modules.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogTailerTests {

  @Test
  void lastLinesReturnsTrailingLines(@TempDir Path tmp) throws IOException {
    Path log = tmp.resolve("daemon.log");
    Files.writeString(log, "one\ntwo\nthree\nfour\nfive\n");

    assertThat(LogTailer.lastLines(log, 2)).containsExactly("four", "five");
  }

  @Test
  void lastLinesReturnsAllWhenFewerThanRequested(@TempDir Path tmp) throws IOException {
    Path log = tmp.resolve("daemon.log");
    Files.writeString(log, "one\ntwo\n");

    assertThat(LogTailer.lastLines(log, 10)).containsExactly("one", "two");
  }

  @Test
  void lastLinesHandlesMissingTrailingNewline(@TempDir Path tmp) throws IOException {
    Path log = tmp.resolve("daemon.log");
    Files.writeString(log, "one\ntwo\nthree");

    assertThat(LogTailer.lastLines(log, 2)).containsExactly("two", "three");
  }

  @Test
  void lastLinesAcrossBlockBoundary(@TempDir Path tmp) throws IOException {
    Path log = tmp.resolve("daemon.log");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      sb.append("line-").append(i).append('\n');
    }
    Files.writeString(log, sb.toString());

    List<String> tail = LogTailer.lastLines(log, 3);
    assertThat(tail).containsExactly("line-4997", "line-4998", "line-4999");
  }

  @Test
  void lastLinesEmptyForMissingOrEmptyFile(@TempDir Path tmp) throws IOException {
    Path missing = tmp.resolve("nope.log");
    Path empty = tmp.resolve("empty.log");
    Files.createFile(empty);

    assertThat(LogTailer.lastLines(missing, 5)).isEmpty();
    assertThat(LogTailer.lastLines(empty, 5)).isEmpty();
    assertThat(LogTailer.lastLines(missing, 0)).isEmpty();
  }

  @Test
  void readFromReturnsOnlyAppendedTextAndAdvances(@TempDir Path tmp) throws IOException {
    Path log = tmp.resolve("daemon.log");
    Files.writeString(log, "first\n");

    LogTailer.Chunk first = LogTailer.readFrom(log, 0);
    assertThat(first.text()).isEqualTo("first\n");

    Files.writeString(log, "second\n", StandardOpenOption.APPEND);
    LogTailer.Chunk second = LogTailer.readFrom(log, first.newPosition());
    assertThat(second.text()).isEqualTo("second\n");

    // Nothing new since last read.
    LogTailer.Chunk none = LogTailer.readFrom(log, second.newPosition());
    assertThat(none.text()).isEmpty();
    assertThat(none.newPosition()).isEqualTo(second.newPosition());
  }

  @Test
  void readFromResetsWhenFileShrinks(@TempDir Path tmp) throws IOException {
    Path log = tmp.resolve("daemon.log");
    Files.writeString(log, "a long original line\n");
    long position = LogTailer.size(log);

    // Simulate rotation/truncation: file becomes shorter than the remembered position.
    Files.write(log, "new\n".getBytes(StandardCharsets.UTF_8));

    LogTailer.Chunk chunk = LogTailer.readFrom(log, position);
    assertThat(chunk.text()).isEqualTo("new\n");
    assertThat(chunk.newPosition()).isEqualTo(LogTailer.size(log));
  }

  @Test
  void sizeIsZeroForMissingFile(@TempDir Path tmp) {
    assertThat(LogTailer.size(tmp.resolve("absent.log"))).isZero();
  }
}
