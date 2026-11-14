package dev.alvo.pieria.harvesters.incremental;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.DocumentFormat;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An in-memory source. Pages hold {@code pageSize} rows and the next page starts {@code overlap} rows back. */
final class FakeFeed implements ChangeFeed<String> {

  private record Entry(String id, String version, Instant changedAt) {
  }

  private final Map<String, Entry> items = new LinkedHashMap<>();
  private final Map<String, HarvesterException> loadFailures = new HashMap<>();
  private final Set<String> vanished = new HashSet<>();
  private Instant now;
  private int pageSize = 100;
  private int overlap;
  private HarvesterException beginFailure;
  private boolean repeatSecondPage;
  final List<Duration> lookbacks = new ArrayList<>();

  FakeFeed(Instant now) {
    this.now = now;
  }

  FakeFeed put(String id, String version, Instant changedAt) {
    items.put(id, new Entry(id, version, changedAt));
    return this;
  }

  FakeFeed remove(String id) {
    items.remove(id);
    return this;
  }

  FakeFeed now(Instant now) {
    this.now = now;
    return this;
  }

  FakeFeed paging(int pageSize, int overlap) {
    this.pageSize = pageSize;
    this.overlap = overlap;
    return this;
  }

  FakeFeed failBegin(HarvesterException failure) {
    this.beginFailure = failure;
    return this;
  }

  FakeFeed failLoad(String id, HarvesterException failure) {
    loadFailures.put(id, failure);
    return this;
  }

  FakeFeed vanish(String id) {
    vanished.add(id);
    return this;
  }

  /** The second page of changes names itself as the next page, as a looping source would. */
  FakeFeed repeatSecondPage() {
    this.repeatSecondPage = true;
    return this;
  }

  @Override
  public Instant begin() throws HarvesterException {
    if (beginFailure != null) {
      throw beginFailure;
    }
    return now;
  }

  @Override
  public Page<Change<String>> changes(Duration lookback, String token) {
    lookbacks.add(lookback);
    List<Entry> matching = items.values().stream()
      .filter(entry -> lookback == null || !entry.changedAt().isBefore(now.minus(lookback)))
      .sorted(Comparator.comparing(Entry::changedAt).thenComparing(Entry::id))
      .toList();
    int start = token == null ? 0 : Integer.parseInt(token);
    Page<Entry> page = page(matching, start);
    String next = repeatSecondPage && start > 0 ? token : page.next();
    return new Page<>(page.entries().stream().map(e -> new Change<>(e.id(), e.version(), e.changedAt(), e.id())).toList(), next);
  }

  @Override
  public Page<String> liveIds(String token) {
    List<Entry> all = items.values().stream().sorted(Comparator.comparing(Entry::id)).toList();
    Page<Entry> page = page(all, token == null ? 0 : Integer.parseInt(token));
    return new Page<>(page.entries().stream().map(Entry::id).toList(), page.next());
  }

  private Page<Entry> page(List<Entry> rows, int start) {
    int end = Math.min(rows.size(), start + pageSize);
    String next = end < rows.size() ? String.valueOf(end - overlap) : null;
    return new Page<>(rows.subList(Math.min(start, rows.size()), end), next);
  }

  @Override
  public HarvestRecord.Item load(Change<String> change) throws HarvesterException {
    HarvesterException failure = loadFailures.get(change.id());
    if (failure != null) {
      throw failure;
    }
    if (vanished.contains(change.id())) {
      return null;
    }
    return new HarvestRecord.Document(change.id(), change.version(), "Item " + change.id(), "https://example.com/" + change.id(),
      null, change.changedAt(), DocumentFormat.MARKDOWN, "text " + change.id(), null);
  }
}
