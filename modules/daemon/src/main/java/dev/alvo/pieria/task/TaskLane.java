package dev.alvo.pieria.task;

import dev.alvo.pieria.ingestion.IngestProgressListener;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Handle used by task producers to report and coordinate one named lane.
 */
public final class TaskLane implements IngestProgressListener {

  private final String name;
  private final TaskProgress owner;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition terminal = lock.newCondition();

  TaskLane(String name, TaskProgress owner) {
    this.name = name;
    this.owner = owner;
  }

  public String name() {
    return name;
  }

  public void start() {
    transition(TaskLaneState.RUNNING, null, 0, 0);
  }

  @Override
  public void onPhase(String phase, int done, int total) {
    transition(TaskLaneState.RUNNING, phase, done, total);
  }

  public void waiting(String phase) {
    TaskLaneSnapshot current = owner.laneSnapshot(name);
    transition(TaskLaneState.WAITING, phase, current.done(), current.total());
  }

  public void complete() {
    TaskLaneSnapshot current = owner.laneSnapshot(name);
    transition(TaskLaneState.COMPLETED, current.phase(), current.done(), current.total());
  }

  public void fail() {
    TaskLaneSnapshot current = owner.laneSnapshot(name);
    transition(TaskLaneState.FAILED, current.phase(), current.done(), current.total());
  }

  public void cancel() {
    TaskLaneSnapshot current = owner.laneSnapshot(name);
    transition(TaskLaneState.CANCELLED, current.phase(), current.done(), current.total());
  }

  /**
   * Wait until this lane reaches a terminal state.
   */
  public void awaitCompletion() {
    lock.lock();
    try {
      while (!owner.laneSnapshot(name).state().terminal()) {
        owner.checkCancelled();
        try {
          terminal.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new TaskCancelledException();
        }
      }
    } finally {
      lock.unlock();
    }
  }

  private void transition(TaskLaneState state, String phase, int done, int total) {
    lock.lock();
    try {
      owner.update(name, state, phase, done, total);
      if (state.terminal()) {
        terminal.signalAll();
      }
    } finally {
      lock.unlock();
    }
  }
}
