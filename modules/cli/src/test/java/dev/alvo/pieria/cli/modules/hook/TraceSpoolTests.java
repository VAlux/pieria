package dev.alvo.pieria.cli.modules.hook;

import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.api.request.TraceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TraceSpoolTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  @TempDir
  Path root;

  private static TraceEventDto event(String args) {
    return new TraceEventDto("Bash", args, "out", TraceStatus.SUCCESS, 0, null, AT, AT);
  }

  @Test
  void appendedEventsDrainInOrder() {
    TraceSpool spool = new TraceSpool(root);
    spool.append("s1", event("first"));
    spool.append("s1", event("second"));

    List<TraceEventDto> drained = spool.drain("s1");

    assertThat(drained).hasSize(2);
    assertThat(drained.get(0).args()).isEqualTo("first");
    assertThat(drained.get(1).args()).isEqualTo("second");
  }

  @Test
  void drainingEmptiesTheSpool() {
    TraceSpool spool = new TraceSpool(root);
    spool.append("s1", event("only"));

    spool.drain("s1");

    assertThat(spool.drain("s1")).isEmpty();
    assertThat(spool.sizeBytes("s1")).isZero();
  }

  @Test
  void drainingAnUnknownSessionIsHarmless() {
    assertThat(new TraceSpool(root).drain("never-existed")).isEmpty();
    assertThat(new TraceSpool(root).sizeBytes("never-existed")).isZero();
    assertThat(new TraceSpool(root).eventCount("never-existed")).isZero();
  }

  @Test
  void sessionsAreIsolated() {
    TraceSpool spool = new TraceSpool(root);
    spool.append("s1", event("one"));
    spool.append("s2", event("two"));

    assertThat(spool.drain("s1")).hasSize(1);
    assertThat(spool.drain("s2")).hasSize(1);
  }

  // A session id arrives from a harness and reaches a file name; it must not be able to escape
  // the spool directory.
  @Test
  void sessionIdsAreSanitizedIntoFileNames() throws IOException {
    TraceSpool spool = new TraceSpool(root);
    spool.append("../../etc/passwd", event("x"));

    try (var files = Files.walk(root)) {
      assertThat(files.filter(Files::isRegularFile))
        .allSatisfy(path -> assertThat(path.normalize()).startsWith(root.normalize()));
    }
    assertThat(spool.drain("../../etc/passwd")).hasSize(1);
  }

  @Test
  void sizeAndCountReportTheCurrentSpool() {
    TraceSpool spool = new TraceSpool(root);
    spool.append("s1", event("one"));
    spool.append("s1", event("two"));

    assertThat(spool.eventCount("s1")).isEqualTo(2);
    assertThat(spool.sizeBytes("s1")).isPositive();
  }

  // A malformed line must not lose the whole spool, matching how the transcript parsers already
  // treat an unparseable record.
  @Test
  void malformedLinesAreSkippedRatherThanFailingTheDrain() throws IOException {
    TraceSpool spool = new TraceSpool(root);
    spool.append("s1", event("good"));
    Files.writeString(root.resolve("s1.ndjson"),
      "\nthis is not json\n", java.nio.file.StandardOpenOption.APPEND);
    spool.append("s1", event("also-good"));

    assertThat(spool.drain("s1")).hasSize(2);
  }

  @Test
  void concurrentAppendsAllSurvive() throws Exception {
    TraceSpool spool = new TraceSpool(root);
    int writers = 8;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(writers);

    for (int i = 0; i < writers; i++) {
      int index = i;
      Thread.ofVirtual().start(() -> {
        try {
          start.await();
          spool.append("s1", event("writer-" + index));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      });
    }
    start.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

    assertThat(spool.drain("s1")).hasSize(writers);
  }

  // A runaway session must degrade, not fill the disk.
  @Test
  void exceedingTheSizeCapDropsTheOldestHalf() {
    TraceSpool spool = new TraceSpool(root, 2048);
    for (int i = 0; i < 200; i++) {
      spool.append("s1", event("event-" + i + "-" + "x".repeat(50)));
    }

    List<TraceEventDto> drained = spool.drain("s1");

    assertThat(drained).isNotEmpty();
    assertThat(drained.size()).isLessThan(200);
    // The tail is what survives: the newest events are the ones worth keeping.
    assertThat(drained.getLast().args()).contains("event-199");
  }

  @Test
  void staleSpoolsAreSweptAndFreshOnesKept() throws IOException {
    TraceSpool spool = new TraceSpool(root);
    spool.append("old", event("x"));
    spool.append("fresh", event("y"));
    Files.setLastModifiedTime(root.resolve("old.ndjson"),
      java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(30L * 86_400)));

    int swept = spool.sweepStale(7);

    assertThat(swept).isEqualTo(1);
    assertThat(spool.drain("fresh")).hasSize(1);
    assertThat(spool.drain("old")).isEmpty();
  }

  // The race the reference implementation had: trim reads-then-rewrites outside the append lock,
  // so a concurrent append landing between the read and the truncating rewrite is silently lost.
  // Many concurrent appends against a tiny cap force trimming to fire repeatedly; every surviving
  // line must still be well-formed NDJSON and the total must never exceed what was sent.
  @Test
  void concurrentAppendsUnderTrimPressureStayCoherent() throws Exception {
    TraceSpool spool = new TraceSpool(root, 2048);
    int writers = 40;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(writers);

    for (int i = 0; i < writers; i++) {
      int index = i;
      Thread.ofVirtual().start(() -> {
        try {
          start.await();
          spool.append("s1", event("writer-" + index + "-" + "x".repeat(30)));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      });
    }
    start.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

    List<TraceEventDto> drained = spool.drain("s1");

    // Trimming keeps only the newest half repeatedly, so the surviving count is bounded but the
    // key assertion is coherence: every drained event is a genuine, whole writer-N event — no
    // partial/corrupt line snuck through, and nothing but drained lines duplicated.
    assertThat(drained).isNotEmpty();
    assertThat(drained.size()).isLessThanOrEqualTo(writers);
    assertThat(drained)
      .extracting(TraceEventDto::args)
      .allSatisfy(args -> assertThat(args).matches("writer-\\d+-x{30}"));
    assertThat(drained).extracting(TraceEventDto::args).doesNotHaveDuplicates();
  }
}
