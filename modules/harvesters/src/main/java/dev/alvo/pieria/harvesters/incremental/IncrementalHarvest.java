package dev.alvo.pieria.harvesters.incremental;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.RecordSink;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a harvest over a {@link ChangeFeed}. Each run lists the changes since the last window, less an
 * overlap that covers clock skew and the source's search lag, then loads and reports them in
 * (changedAt, id) order. The listing finishes before anything is loaded, because loading waits on the
 * daemon's extraction, and a source paging by offset would move under a slow run. A run cut short
 * resumes after the last change it reported; the overlap is answered from the cursor's recent states.
 * Deletions are found by a separate pass that lists every live id, at most once per sweep interval and
 * only after the window is complete, so a run cut short by a limit or failure never deletes anything.
 */
public final class IncrementalHarvest {

  public static final Duration OVERLAP = Duration.ofMinutes(10);
  public static final int CHECKPOINT_EVERY = 25;
  public static final int LOAD_BATCH = 25;
  /** How many changes one listing holds before it stops and the run works through them. */
  public static final int MAX_PENDING = 20_000;

  private IncrementalHarvest() {
  }

  public static <T> void run(ChangeFeed<T> feed, HarvestRequest request, RecordSink sink, Duration sweepEvery, String label)
    throws HarvesterException {
    Instant now = feed.begin();
    new Run<>(feed, request.limits(), sink, ChangeCursor.read(request.cursor()), label).run(now, sweepEvery);
  }

  private static final class Run<T> {

    private final ChangeFeed<T> feed;
    private final HarvestRequest.Limits limits;
    private final RecordSink sink;
    private final ChangeCursor cursor;
    private final String label;
    private int emitted;
    private long chars;

    Run(ChangeFeed<T> feed, HarvestRequest.Limits limits, RecordSink sink, ChangeCursor cursor, String label) {
      this.feed = feed;
      this.limits = limits;
      this.sink = sink;
      this.cursor = cursor;
      this.label = label;
    }

    void run(Instant now, Duration sweepEvery) throws HarvesterException {
      try {
        if (cursor.windowStart() == null) {
          cursor.windowStart(now);
          cursor.after(null);
        }
        if (!reportWindow()) {
          checkpoint();
          return;
        }
        cursor.since(cursor.windowStart());
        cursor.windowStart(null);
        cursor.after(null);
        cursor.prune(cursor.since().minus(OVERLAP));
        if (cursor.lastSweep() != null && Duration.between(cursor.lastSweep(), now).compareTo(sweepEvery) < 0) {
          checkpoint();
          return;
        }
        Set<String> live = liveIds();
        if (live.isEmpty()) {
          checkpoint();
          return;
        }
        List<String> ids = new ArrayList<>(live);
        for (int from = 0; from < ids.size(); from += ProtocolLimits.MAX_SEEN_IDS) {
          sink.emit(new HarvestRecord.Seen(ids.subList(from, Math.min(from + ProtocolLimits.MAX_SEEN_IDS, ids.size()))));
        }
        cursor.lastSweep(now);
        checkpoint();
      } catch (HarvesterException | RuntimeException e) {
        // Everything reported so far is stored; the next run carries on from here.
        checkpoint();
        throw e;
      }
      sink.emit(new HarvestRecord.Sweep());
    }

    /** Returns false when a run limit stopped the window before its end. */
    private boolean reportWindow() throws HarvesterException {
      while (true) {
        Instant from = cursor.since() == null ? null : cursor.since().minus(OVERLAP);
        Pending<T> pending = list(from);
        if (!report(pending.changes())) {
          return false;
        }
        if (pending.complete()) {
          return true;
        }
      }
    }

    private record Pending<T>(List<Change<T>> changes, boolean complete) {
    }

    /** The window's changes still to report, in (changedAt, id) order, with the latest state of each item. */
    private Pending<T> list(Instant from) throws HarvesterException {
      Map<String, Change<T>> pending = new LinkedHashMap<>();
      Set<String> tokens = new HashSet<>();
      String token = null;
      boolean complete = true;
      do {
        Page<Change<T>> page = feed.changes(from, token);
        for (Change<T> change : page.entries()) {
          if (cursor.reported(change.id(), change.version()) || done(change)) {
            pending.remove(change.id());
            continue;
          }
          Change<T> known = pending.get(change.id());
          if (known == null || change.changedAt().isAfter(known.changedAt())) {
            pending.put(change.id(), change);
          }
        }
        token = next(page.next(), tokens);
        if (token != null && pending.size() >= MAX_PENDING) {
          complete = false;
          break;
        }
      } while (token != null);
      List<Change<T>> ordered = new ArrayList<>(pending.values());
      ordered.sort(Comparator.comparing(IncrementalHarvest::key));
      return new Pending<>(ordered, complete);
    }

    private boolean done(Change<T> change) {
      return cursor.after() != null && key(change).compareTo(cursor.after()) <= 0;
    }

    /** Returns false when a run limit stopped before the end of {@code changes}. */
    private boolean report(List<Change<T>> changes) throws HarvesterException {
      for (int from = 0; from < changes.size(); ) {
        int quota = limits.maxItemsPerRun() == null ? LOAD_BATCH : limits.maxItemsPerRun() - emitted;
        if (quota <= 0) {
          return false;
        }
        List<Change<T>> batch = changes.subList(from, Math.min(changes.size(), from + Math.min(LOAD_BATCH, quota)));
        Map<String, HarvestRecord.Item> loaded = new HashMap<>();
        for (HarvestRecord.Item item : feed.load(batch)) {
          loaded.put(item.id(), item);
        }
        for (Change<T> change : batch) {
          HarvestRecord.Item item = loaded.get(change.id());
          if (item != null) {
            long size = size(item);
            if (limits.maxCharsPerRun() != null && emitted > 0 && chars + size > limits.maxCharsPerRun()) {
              return false;
            }
            emit(item, size);
            cursor.remember(change.id(), change.version(), change.changedAt());
          } else if (feed.tombstonesGone()) {
            // Not an item, so it counts toward neither run limit.
            sink.emit(new HarvestRecord.Tombstone(change.id()));
            cursor.remember(change.id(), change.version(), change.changedAt());
          }
          // A change that has gone since it was listed is done too; a sweep or the tombstone removes it.
          cursor.after(key(change));
          if (cursor.since() == null || change.changedAt().isAfter(cursor.since())) {
            cursor.since(change.changedAt());
          }
          cursor.prune(cursor.since().minus(OVERLAP));
        }
        from += batch.size();
      }
      return true;
    }

    private void emit(HarvestRecord.Item item, long size) {
      sink.emit(item);
      emitted++;
      chars += size;
      sink.emit(new HarvestRecord.Progress(emitted, null));
      if (emitted % CHECKPOINT_EVERY == 0) {
        checkpoint();
      }
    }

    private Set<String> liveIds() throws HarvesterException {
      Set<String> ids = new LinkedHashSet<>();
      Set<String> tokens = new HashSet<>();
      String token = null;
      do {
        Page<String> page = feed.liveIds(token);
        ids.addAll(page.entries());
        token = next(page.next(), tokens);
      } while (token != null);
      return ids;
    }

    private String next(String token, Set<String> used) throws HarvesterException {
      if (token != null && !used.add(token)) {
        throw new HarvesterException(ErrorClass.TRANSIENT, label + " returned a page it had already sent");
      }
      return token;
    }

    private static long size(HarvestRecord.Item item) {
      return switch (item) {
        case HarvestRecord.Document document -> document.text().length();
        case HarvestRecord.ConversationThread thread -> thread.turns().stream().mapToLong(turn -> turn.text().length()).sum();
      };
    }

    private void checkpoint() {
      sink.emit(new HarvestRecord.Checkpoint(cursor.toJson()));
    }
  }

  private static ChangeCursor.Key key(Change<?> change) {
    return new ChangeCursor.Key(change.changedAt(), change.id());
  }
}
