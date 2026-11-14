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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs a harvest over a {@link ChangeFeed}. Each run asks for the changes since the last window, less
 * an overlap that covers clock skew and the source's search lag, and reports each item state once.
 * Deletions are found by a separate pass that lists every live id, at most once per sweep interval and
 * only after the window is complete, so a run cut short by a limit or failure never deletes anything.
 */
public final class IncrementalHarvest {

  public static final Duration OVERLAP = Duration.ofMinutes(10);
  public static final int CHECKPOINT_EVERY = 25;

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
        }
        if (!reportChanges(now)) {
          checkpoint();
          return;
        }
        cursor.since(cursor.windowStart());
        cursor.windowStart(null);
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
    private boolean reportChanges(Instant now) throws HarvesterException {
      Duration lookback = null;
      if (cursor.since() != null) {
        Duration elapsed = Duration.between(cursor.since(), now);
        lookback = (elapsed.isNegative() ? Duration.ZERO : elapsed).plus(OVERLAP);
      }
      Set<String> tokens = new HashSet<>();
      String token = null;
      do {
        Page<Change<T>> page = feed.changes(lookback, token);
        for (Change<T> change : page.entries()) {
          if (cursor.reported(change.id(), change.version())) {
            continue;
          }
          if (limits.maxItemsPerRun() != null && emitted >= limits.maxItemsPerRun()) {
            return false;
          }
          HarvestRecord.Item item = feed.load(change);
          if (item == null) {
            continue;
          }
          long size = size(item);
          if (limits.maxCharsPerRun() != null && emitted > 0 && chars + size > limits.maxCharsPerRun()) {
            return false;
          }
          report(change, item, size);
        }
        token = next(page.next(), tokens);
      } while (token != null);
      return true;
    }

    private void report(Change<T> change, HarvestRecord.Item item, long size) {
      sink.emit(item);
      emitted++;
      chars += size;
      cursor.remember(change.id(), change.version(), change.changedAt());
      if (cursor.since() == null || change.changedAt().isAfter(cursor.since())) {
        cursor.since(change.changedAt());
      }
      cursor.prune(cursor.since().minus(OVERLAP));
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
}
