package dev.alvo.pieria.mcp;

import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.RememberRequest;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Model-facing MCP tools. Each tool forwards to the daemon's REST surface via
 * {@link DaemonClient}; the gateway itself holds no state. {@code ingest} is intentionally absent —
 * bulk ingestion is a harness hook, not a model tool, to keep the model's surface narrow.
 *
 * <p>The profile name defaults to the resolved profile (git remote / cwd / {@code PIERIA_PROFILE},
 * via {@code ProfileResolver}) but every tool accepts an optional {@code profile} override.
 *
 * <p>Daemon-down errors are caught and returned as a concise string so the model gets actionable
 * feedback instead of a stack trace.
 */
public class MemoryTools {

  private final DaemonClient client;
  private final String defaultProfile;

  public MemoryTools(DaemonClient client, String defaultProfile) {
    this.client = client;
    this.defaultProfile = defaultProfile;
  }

  @Tool(name = "recall", description = """
    Recall relevant memories — prior decisions, conventions, rejected approaches, and gotchas — \
    for a query and return a synthesized answer. Call this BEFORE planning a non-trivial task, \
    or when you hit a choice that earlier context might already settle, so you don't relitigate \
    or contradict what was decided. Retrieval runs a full search-and-synthesis pipeline and can \
    take tens of seconds, so call it deliberately at task boundaries, not on every turn.""")
  public String recall(
    @ToolParam(description = "Natural-language query describing what you need context on") String query,
    @ToolParam(required = false, description = "Max memories to consider") Integer limit,
    @ToolParam(required = false, description = "Profile name override") String profile) {
    return guarded(() -> client.recall(profile(profile), new RecallRequest(query, limit, null)));
  }

  @Tool(name = "remember", description = """
    Store a single high-signal memory explicitly. Use this in the moment after settling \
    something non-obvious: a design decision, a constraint, a convention the user insists on, \
    or a notable event. Routine bulk capture of the conversation is handled automatically by \
    harness hooks, so reserve this for facts worth pinning on their own.""")
  public String remember(
    @ToolParam(description = """
      Memory type: 'fact' for design decisions and constraints, 'instruction' for conventions \
      or preferences the user insists on, 'event' for notable occurrences, 'task' for work \
      items (tasks are listable and searchable but not vector-indexed).""") String type,
    @ToolParam(description = "Memory content") String content,
    @ToolParam(required = false, description = "Session id") String sessionId,
    @ToolParam(required = false, description = """
      Stable key for keyed supersession (e.g. 'embedding-dimension'). When set, a new memory \
      with the same key replaces the prior value for that key instead of accumulating a \
      duplicate. Use it for facts whose value changes over time.""") String topicKey,
    @ToolParam(required = false, description = "Opaque payload string") String payload,
    @ToolParam(required = false, description = "Profile name override") String profile) {
    return guarded(() -> client.remember(profile(profile), new RememberRequest(type, content, sessionId, topicKey, payload)));
  }

  @Tool(name = "list", description = """
    List stored memories, optionally filtered by type and session. Useful to audit or browse \
    what is remembered without paying for the full recall pipeline.""")
  public String list(
    @ToolParam(required = false, description = "Filter by memory type") String type,
    @ToolParam(required = false, description = "Filter by session id") String session,
    @ToolParam(required = false, description = "Profile name override") String profile) {
    return guarded(() -> client.list(profile(profile), type, session));
  }

  @Tool(name = "forget", description = """
    Forget (delete) a memory by its id. Keyed facts supersede automatically via topicKey, so \
    reserve this for explicitly removing a memory that is wrong or no longer wanted.""")
  public String forget(
    @ToolParam(description = "Memory id to forget") String id,
    @ToolParam(required = false, description = "Profile name override") String profile) {
    return guarded(() -> client.forget(profile(profile), id));
  }

  private String profile(String override) {
    return (override != null && !override.isBlank()) ? override : defaultProfile;
  }

  /**
   * Translates a daemon-down failure into a concise tool error string instead of throwing.
   */
  private String guarded(java.util.function.Supplier<String> call) {
    try {
      return call.get();
    } catch (DaemonUnavailableException e) {
      return e.getMessage();
    }
  }
}
