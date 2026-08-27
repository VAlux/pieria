package dev.alvo.pieria.task;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Mutable task-scoped owner of ordered, named progress lanes.
 */
public final class TaskProgress {

  private final AtomicReference<TaskSnapshot> snapshot;
  private final BooleanSupplier cancelled;
  private final Map<String, TaskLane> lanes = new LinkedHashMap<>();

  TaskProgress(AtomicReference<TaskSnapshot> snapshot, BooleanSupplier cancelled) {
    this.snapshot = snapshot;
    this.cancelled = cancelled;
  }

  /**
   * Return the stable handle for {@code name}, creating a queued lane on first use.
   */
  public synchronized TaskLane lane(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("task lane name must not be blank");
    }
    checkCancelled();
    return lanes.computeIfAbsent(name, laneName -> {
      snapshot.updateAndGet(current -> current.addLane(laneName));
      return new TaskLane(laneName, this);
    });
  }

  public void checkCancelled() {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
      throw new TaskCancelledException();
    }
  }

  void update(String name, TaskLaneState state, String phase, int done, int total) {
    checkCancelled();
    snapshot.updateAndGet(current -> current.updateLane(name, state, phase, done, total));
  }

  TaskLaneSnapshot laneSnapshot(String name) {
    return snapshot.get().lanes().stream()
      .filter(lane -> lane.name().equals(name))
      .findFirst()
      .orElseThrow(() -> new IllegalStateException("unknown task lane " + name));
  }
}
