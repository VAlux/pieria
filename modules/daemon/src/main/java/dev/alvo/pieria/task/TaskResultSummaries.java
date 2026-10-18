package dev.alvo.pieria.task;

import tools.jackson.databind.JsonNode;

/**
 * One-line descriptions of a finished task's result for the console's task list. Results differ by
 * task kind, so this reads whichever well-known counters the result carries.
 */
public final class TaskResultSummaries {

  private TaskResultSummaries() {
  }

  /** {@code null} when the result is absent or has none of the known counters. */
  public static String summarize(JsonNode result) {
    if (result == null || !result.isObject()) return null;
    if (result.has("documents")) {
      return count(result, "documents", "document") + " · " + count(result, "memoriesStored", "memory", "memories") + " stored";
    }
    if (result.has("filesParsed")) {
      return count(result, "filesParsed", "file") + " parsed · " + count(result, "symbols", "symbol");
    }
    if (result.has("memoriesAdopted")) {
      return result.path("memoriesAdopted").asLong() + " of " + count(result, "memoriesScanned", "memory", "memories") + " adopted";
    }
    if (result.has("enqueued") && result.has("considered")) {
      return count(result, "enqueued", "article") + " queued of " + result.path("considered").asLong() + " pages";
    }
    if (result.has("count")) {
      return count(result, "count", "memory", "memories") + " stored";
    }
    return null;
  }

  private static String count(JsonNode result, String field, String one) {
    return count(result, field, one, one + "s");
  }

  private static String count(JsonNode result, String field, String one, String many) {
    long n = result.path(field).asLong();
    return n + " " + (n == 1 ? one : many);
  }
}
