package dev.alvo.pieria.cli.log;

import dev.alvo.pieria.api.response.TaskLaneProgress;

import java.util.List;

/** Sink for complete ordered lane snapshots observed while polling an asynchronous task. */
@FunctionalInterface
public interface ProgressListener {

  void onProgress(List<TaskLaneProgress> lanes);

  static ProgressListener noop() {
    return ignored -> { };
  }
}
