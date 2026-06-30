package dev.alvo.pieria.api.response;

import java.util.List;

/**
 * All known async tasks, returned by {@code GET /v1/tasks}: running tasks plus those that finished
 * within the daemon's terminal retention window, newest first.
 */
public record TaskListResponse(List<TaskSummary> tasks) {
}
