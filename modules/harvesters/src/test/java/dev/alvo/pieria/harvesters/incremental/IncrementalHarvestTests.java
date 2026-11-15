package dev.alvo.pieria.harvesters.incremental;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.RecordSink;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncrementalHarvestTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");
  private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
  private static final Duration DAY = Duration.ofHours(24);

  /** One run's records, and the cursor the next run gets. */
  private record Run(List<HarvestRecord> records, JsonNode cursor) {

    List<String> itemIds() {
      return records.stream().filter(r -> r instanceof HarvestRecord.Item).map(r -> ((HarvestRecord.Item) r).id()).toList();
    }

    List<String> seenIds() {
      return records.stream().filter(r -> r instanceof HarvestRecord.Seen).flatMap(r -> ((HarvestRecord.Seen) r).ids().stream()).toList();
    }

    boolean swept() {
      return records.stream().anyMatch(r -> r instanceof HarvestRecord.Sweep);
    }

    List<Class<?>> kinds() {
      return records.stream().filter(r -> !(r instanceof HarvestRecord.Progress)).<Class<?>>map(Object::getClass).toList();
    }
  }

  private static Run run(FakeFeed feed, JsonNode cursor, HarvestRequest.Limits limits) throws HarvesterException {
    List<HarvestRecord> records = Collections.synchronizedList(new ArrayList<>());
    RecordSink sink = records::add;
    HarvestRequest request = new HarvestRequest(1, MAPPER.createObjectNode(), Map.of(), cursor, limits);
    IncrementalHarvest.run(feed, request, sink, DAY, "fake");
    return new Run(List.copyOf(records), lastCursor(records, cursor));
  }

  private static Run run(FakeFeed feed, JsonNode cursor) throws HarvesterException {
    return run(feed, cursor, HarvestRequest.Limits.NONE);
  }

  private static JsonNode lastCursor(List<HarvestRecord> records, JsonNode previous) {
    JsonNode cursor = previous;
    for (HarvestRecord record : records) {
      if (record instanceof HarvestRecord.Checkpoint checkpoint) {
        cursor = checkpoint.cursor();
      }
    }
    return cursor;
  }

  private static FakeFeed threeItems() {
    return new FakeFeed(NOW).put("a", "1", T).put("b", "1", T.plusSeconds(60)).put("c", "1", T.plusSeconds(120));
  }

  @Test
  void firstRunReportsEverythingThenSweeps() throws HarvesterException {
    FakeFeed feed = threeItems();

    Run run = run(feed, null);

    assertThat(run.itemIds()).containsExactly("a", "b", "c");
    assertThat(run.seenIds()).containsExactlyInAnyOrder("a", "b", "c");
    List<Class<?>> kinds = run.kinds();
    assertThat(kinds.subList(kinds.size() - 3, kinds.size()))
      .containsExactly(HarvestRecord.Seen.class, HarvestRecord.Checkpoint.class, HarvestRecord.Sweep.class);
    assertThat(run.records().getLast()).isInstanceOf(HarvestRecord.Sweep.class);
    assertThat(feed.froms.getFirst()).isNull();
  }

  @Test
  void secondRunAsksOnlyForTheWindowPlusOverlap() throws HarvesterException {
    FakeFeed feed = threeItems();
    Run first = run(feed, null);
    feed.now(NOW.plus(Duration.ofHours(2)));
    feed.froms.clear();

    run(feed, first.cursor());

    assertThat(feed.froms.getFirst()).isEqualTo(NOW.minus(IncrementalHarvest.OVERLAP));
  }

  @Test
  void listsTheWholeWindowBeforeLoadingAny() throws HarvesterException {
    FakeFeed feed = new FakeFeed(NOW).paging(2, 0);
    for (int i = 0; i < 7; i++) {
      feed.put("i" + i, "1", T.plusSeconds(i));
    }

    run(feed, null);

    // Pages are read back to back, so a source's moving "now" can't overtake them while items are ingested.
    int firstLoad = feed.events.indexOf("load");
    assertThat(feed.events.subList(0, firstLoad)).containsOnly("list").hasSize(4);
    assertThat(feed.events.subList(firstLoad, feed.events.size())).containsOnly("load");
  }

  @Test
  void moreChangesThanTheRecentCapInOneWindowStillFinish() throws HarvesterException {
    FakeFeed feed = new FakeFeed(NOW).paging(5_000, 0);
    int count = ChangeCursor.MAX_RECENT + 2_000;
    for (int i = 0; i < count; i++) {
      feed.put(String.format("i%05d", i), "1", T.plusMillis(i));
    }
    HarvestRequest.Limits limit = new HarvestRequest.Limits(1_000, null);
    java.util.Set<String> reported = new java.util.HashSet<>();
    int total = 0;
    JsonNode cursor = null;
    boolean swept = false;
    for (int i = 0; i < 20 && !swept; i++) {
      Run run = run(feed, cursor, limit);
      reported.addAll(run.itemIds());
      total += run.itemIds().size();
      cursor = run.cursor();
      swept = run.swept();
    }

    assertThat(swept).isTrue();
    assertThat(reported).hasSize(count);
    assertThat(total).isEqualTo(count);
  }

  @Test
  void unchangedItemsInTheOverlapAreNotReportedAgain() throws HarvesterException {
    FakeFeed feed = new FakeFeed(NOW).put("a", "1", NOW.minusSeconds(30));
    Run first = run(feed, null);
    feed.now(NOW.plusSeconds(60));

    Run second = run(feed, first.cursor());

    assertThat(first.itemIds()).containsExactly("a");
    assertThat(second.itemIds()).isEmpty();
  }

  @Test
  void anEditedItemIsReportedAgain() throws HarvesterException {
    FakeFeed feed = new FakeFeed(NOW).put("a", "1", NOW.minusSeconds(30)).put("b", "1", NOW.minusSeconds(20));
    Run first = run(feed, null);
    feed.now(NOW.plusSeconds(60)).put("a", "2", NOW.plusSeconds(40));

    Run second = run(feed, first.cursor());

    assertThat(second.itemIds()).containsExactly("a");
    HarvestRecord.Document edited = (HarvestRecord.Document) second.records().stream()
      .filter(r -> r instanceof HarvestRecord.Document).findFirst().orElseThrow();
    assertThat(edited.version()).isEqualTo("2");
  }

  @Test
  void sweepWaitsForItsInterval() throws HarvesterException {
    FakeFeed feed = threeItems();
    Run first = run(feed, null);
    feed.now(NOW.plus(Duration.ofHours(1)));

    Run second = run(feed, first.cursor());

    assertThat(second.seenIds()).isEmpty();
    assertThat(second.swept()).isFalse();
    assertThat(second.records().getLast()).isInstanceOf(HarvestRecord.Checkpoint.class);

    feed.now(NOW.plus(Duration.ofHours(25)));
    Run third = run(feed, second.cursor());
    assertThat(third.swept()).isTrue();
    assertThat(third.seenIds()).containsExactlyInAnyOrder("a", "b", "c");
  }

  @Test
  void itemLimitStopsWithoutSweepAndResumeLosesNothing() throws HarvesterException {
    FakeFeed feed = threeItems();
    HarvestRequest.Limits one = new HarvestRequest.Limits(1, null);
    List<String> reported = new ArrayList<>();
    JsonNode cursor = null;
    Run run;
    int runs = 0;
    do {
      run = run(feed, cursor, one);
      reported.addAll(run.itemIds());
      cursor = run.cursor();
      runs++;
      assertThat(run.itemIds()).hasSizeLessThanOrEqualTo(1);
    } while (!run.swept() && runs < 10);

    assertThat(run.swept()).isTrue();
    assertThat(reported).containsExactly("a", "b", "c");
    assertThat(run.seenIds()).containsExactlyInAnyOrder("a", "b", "c");
  }

  @Test
  void manyItemsInOneMinuteDoNotLivelock() throws HarvesterException {
    FakeFeed feed = new FakeFeed(NOW);
    for (String id : List.of("a", "b", "c", "d", "e")) {
      feed.put(id, "1", T);
    }
    HarvestRequest.Limits two = new HarvestRequest.Limits(2, null);
    List<String> reported = new ArrayList<>();
    JsonNode cursor = null;
    boolean swept = false;
    for (int i = 0; i < 3 && !swept; i++) {
      Run run = run(feed, cursor, two);
      reported.addAll(run.itemIds());
      cursor = run.cursor();
      swept = run.swept();
    }

    assertThat(swept).isTrue();
    assertThat(reported).containsExactly("a", "b", "c", "d", "e");
  }

  @Test
  void charLimitLetsTheFirstItemThrough() throws HarvesterException {
    Run run = run(threeItems(), null, new HarvestRequest.Limits(null, 1L));

    assertThat(run.itemIds()).containsExactly("a");
    assertThat(run.swept()).isFalse();
    assertThat(run.records().getLast()).isInstanceOf(HarvestRecord.Checkpoint.class);
  }

  @Test
  void duplicateRowsFromOverlappingPagesAreReportedOnce() throws HarvesterException {
    FakeFeed feed = new FakeFeed(NOW).paging(3, 2);
    for (int i = 0; i < 7; i++) {
      feed.put("i" + i, "1", T.plusSeconds(i));
    }

    Run run = run(feed, null);

    assertThat(run.itemIds()).containsExactly("i0", "i1", "i2", "i3", "i4", "i5", "i6");
    assertThat(run.seenIds()).containsExactly("i0", "i1", "i2", "i3", "i4", "i5", "i6");
  }

  @Test
  void repeatedPageTokenFailsTransient() {
    FakeFeed feed = new FakeFeed(NOW).paging(2, 0).repeatSecondPage();
    for (int i = 0; i < 6; i++) {
      feed.put("i" + i, "1", T.plusSeconds(i));
    }

    assertThatThrownBy(() -> run(feed, null)).isInstanceOfSatisfying(HarvesterException.class, e -> {
      assertThat(e.errorClass()).isEqualTo(ErrorClass.TRANSIENT);
      assertThat(e.getMessage()).contains("fake returned a page it had already sent");
    });
  }

  @Test
  void authFailureEmitsNothing() {
    FakeFeed feed = threeItems().failBegin(new HarvesterException(ErrorClass.AUTH, "rejected"));
    List<HarvestRecord> records = new ArrayList<>();
    HarvestRequest request = new HarvestRequest(1, MAPPER.createObjectNode(), Map.of(), null, null);

    assertThatThrownBy(() -> IncrementalHarvest.run(feed, request, records::add, DAY, "fake"))
      .isInstanceOfSatisfying(HarvesterException.class, e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.AUTH));
    assertThat(records).isEmpty();
  }

  @Test
  void failureMidRunCheckpointsFirst() throws HarvesterException {
    FakeFeed feed = new FakeFeed(NOW);
    for (int i = 0; i < IncrementalHarvest.LOAD_BATCH + 5; i++) {
      feed.put(String.format("i%03d", i), "1", T.plusSeconds(i));
    }
    String failing = String.format("i%03d", IncrementalHarvest.LOAD_BATCH + 2);
    feed.failLoad(failing, new HarvesterException(ErrorClass.RATE_LIMITED, "slow down", 60L));
    List<HarvestRecord> records = new ArrayList<>();
    HarvestRequest request = new HarvestRequest(1, MAPPER.createObjectNode(), Map.of(), null, null);

    assertThatThrownBy(() -> IncrementalHarvest.run(feed, request, records::add, DAY, "fake"))
      .isInstanceOfSatisfying(HarvesterException.class, e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.RATE_LIMITED));
    Run run = new Run(records, lastCursor(records, null));
    assertThat(run.itemIds()).hasSize(IncrementalHarvest.LOAD_BATCH);
    assertThat(records.getLast()).isInstanceOf(HarvestRecord.Checkpoint.class);
    assertThat(run.swept()).isFalse();

    feed.failLoad(failing, null);
    Run resumed = run(feed, run.cursor());
    assertThat(resumed.itemIds()).hasSize(5).doesNotContainAnyElementsOf(run.itemIds());
  }

  @Test
  void emptySourceNeverSweeps() throws HarvesterException {
    Run run = run(new FakeFeed(NOW), null);

    assertThat(run.swept()).isFalse();
    assertThat(run.records()).isNotEmpty();
    assertThat(run.records().getLast()).isInstanceOf(HarvestRecord.Checkpoint.class);
  }

  @Test
  void vanishedItemIsSkipped() throws HarvesterException {
    FakeFeed feed = threeItems().vanish("b");

    Run run = run(feed, null);

    assertThat(run.itemIds()).containsExactly("a", "c");
    assertThat(run.swept()).isTrue();
  }

  @Test
  void deletedItemIsLeftOutOfTheNextSweep() throws HarvesterException {
    FakeFeed feed = threeItems();
    Run first = run(feed, null);
    feed.remove("b").now(NOW.plus(Duration.ofHours(25)));

    Run second = run(feed, first.cursor());

    assertThat(second.swept()).isTrue();
    assertThat(second.seenIds()).containsExactlyInAnyOrder("a", "c");
  }

  /** Changed a minute ago, so a second run lists both again inside its overlap. */
  private static FakeFeed recentPair() {
    return new FakeFeed(NOW).put("a", "1", NOW.minusSeconds(120)).put("b", "1", NOW.minusSeconds(60)).vanish("b");
  }

  @Test
  void goneChangeBecomesATombstoneWhenTheFeedSaysSo() throws HarvesterException {
    FakeFeed feed = recentPair().reportGone();

    Run first = run(feed, null);

    List<HarvestRecord> records = first.records();
    assertThat(records.subList(0, 3)).containsExactly(records.getFirst(), new HarvestRecord.Progress(1, null), new HarvestRecord.Tombstone("b"));
    assertThat(first.itemIds()).containsExactly("a");
    int lastCheckpoint = 0;
    for (int i = 0; i < records.size(); i++) {
      if (records.get(i) instanceof HarvestRecord.Checkpoint) {
        lastCheckpoint = i;
      }
    }
    assertThat(lastCheckpoint).isGreaterThan(2);

    feed.now(NOW.plusSeconds(60));
    Run second = run(feed, first.cursor());

    assertThat(second.itemIds()).isEmpty();
    assertThat(second.records()).noneMatch(record -> record instanceof HarvestRecord.Tombstone);
    assertThat(feed.froms.getLast()).isBefore(NOW.minusSeconds(60));
  }

  @Test
  void goneChangeIsSilentByDefault() throws HarvesterException {
    Run run = run(recentPair(), null);

    assertThat(run.itemIds()).containsExactly("a");
    assertThat(run.records()).noneMatch(record -> record instanceof HarvestRecord.Tombstone);
  }

  @Test
  void tombstonesDoNotCountTowardTheItemLimit() throws HarvesterException {
    FakeFeed feed = new FakeFeed(NOW).put("x", "1", T).put("y", "1", T.plusSeconds(1)).put("z", "1", T.plusSeconds(2))
      .vanish("x").reportGone();

    Run first = run(feed, null, new HarvestRequest.Limits(1, null));

    assertThat(first.records()).contains(new HarvestRecord.Tombstone("x"));
    assertThat(first.itemIds()).containsExactly("y");
    Run second = run(feed, first.cursor(), new HarvestRequest.Limits(1, null));
    assertThat(second.itemIds()).containsExactly("z");
    assertThat(second.records()).noneMatch(record -> record instanceof HarvestRecord.Tombstone);
  }
}
