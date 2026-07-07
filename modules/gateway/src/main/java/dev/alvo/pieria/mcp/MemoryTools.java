package dev.alvo.pieria.mcp;

import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.client.exception.DaemonHttpException;
import dev.alvo.pieria.client.exception.DaemonUnavailableException;
import dev.alvo.pieria.client.ProfileClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Model-facing MCP tools. Each tool forwards to the daemon's REST surface via
 * {@link ProfileClient}; the gateway itself holds no state. {@code ingest} is intentionally absent —
 * bulk ingestion is a harness hook, not a model tool, to keep the model's surface narrow.
 *
 * <p>The profile name defaults to the resolved profile (git remote / cwd / {@code PIERIA_PROFILE},
 * via {@code ProfileResolver}) but every tool accepts an optional {@code profile} override.
 *
 * <p>Daemon-down errors are caught and returned as a concise string so the model gets actionable
 * feedback instead of a stack trace.
 */
public class MemoryTools {

  private final ProfileClient client;
  private final String defaultProfile;

  public MemoryTools(ProfileClient client, String defaultProfile) {
    this.client = client;
    this.defaultProfile = defaultProfile;
  }

  @Tool(name = "recall", description = """
    Recall relevant memories — prior decisions, conventions, rejected approaches, and gotchas — \
    for a query. Call this BEFORE planning a non-trivial task, or when you hit a choice that earlier \
    context might already settle, so you don't relitigate or contradict what was decided. Call it \
    deliberately at task boundaries, not on every turn. The 'mode' parameter trades latency/cost for \
    answer richness — the default synthesizes a written answer and can take tens of seconds; the \
    cheaper tiers return the raw memories with no synthesized answer in a few seconds.""")
  public String recall(
    @ToolParam(description = "Natural-language query describing what you need context on") String query,
    @ToolParam(required = false, description = "Max memories to consider") Integer limit,
    @ToolParam(required = false, description = """
      Inference tier (default 'synthesized'): 'synthesized' runs the full pipeline and returns a \
      written answer synthesized from the memories (tens of seconds); 'analyzed' runs model-driven \
      retrieval but returns only the raw memories, no answer (a few seconds); 'evidence' is the \
      fastest — deterministic retrieval, raw memories, no answer (~1-3s). Use a cheaper tier when \
      you just want the underlying memories rather than a composed answer.""") String mode,
    @ToolParam(required = false, description = "Profile name override") String profile) {
    return guarded(() -> client.toJson(client.recall(profile(profile),
      new RecallRequest(query, limit, null, parseMode(mode)))));
  }

  /** Lenient tier parse for the model-facing tool: blank or unrecognized values defer to the default. */
  private static RecallMode parseMode(String mode) {
    try {
      return RecallMode.fromWire(mode);
    } catch (IllegalArgumentException unrecognized) {
      return null;
    }
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
    return guarded(() -> client.toJson(client.remember(
      profile(profile), new RememberRequest(type, content, sessionId, topicKey, payload))));
  }

  @Tool(name = "list", description = """
    List stored memories, optionally filtered by type and session. Useful to audit or browse \
    what is remembered without paying for the full recall pipeline.""")
  public String list(
    @ToolParam(required = false, description = "Filter by memory type") String type,
    @ToolParam(required = false, description = "Filter by session id") String session,
    @ToolParam(required = false, description = "Profile name override") String profile) {
    return guarded(() -> client.toJson(client.memories(profile(profile), type, session)));
  }

  @Tool(name = "forget", description = """
    Forget (delete) a memory by its id. Keyed facts supersede automatically via topicKey, so \
    reserve this for explicitly removing a memory that is wrong or no longer wanted.""")
  public String forget(
    @ToolParam(description = "Memory id to forget") String id,
    @ToolParam(required = false, description = "Profile name override") String profile) {
    return guarded(() -> { client.forget(profile(profile), id); return "204 No Content"; });
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
    } catch (DaemonHttpException e) {
      return e.body().isBlank() ? e.getMessage() : e.body();
    }
  }
}
