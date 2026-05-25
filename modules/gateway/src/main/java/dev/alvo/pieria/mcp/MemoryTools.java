package dev.alvo.pieria.mcp;

import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.RememberRequest;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Model-facing MCP tools (SPEC 10.1). Each tool forwards to the daemon's REST surface via
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

  @Tool(name = "recall", description = "Recall relevant memories for a query and return a synthesized answer.")
  public String recall(
    @ToolParam(description = "Natural-language query") String query,
    @ToolParam(required = false, description = "Max memories to consider") Integer limit,
    @ToolParam(required = false, description = "Profile name override") String profile) {
    return guarded(() -> client.recall(profile(profile), new RecallRequest(query, limit, null)));
  }

  @Tool(name = "remember", description = "Store a single memory explicitly.")
  public String remember(
    @ToolParam(description = "Memory type: fact, event, instruction, or task") String type,
    @ToolParam(description = "Memory content") String content,
    @ToolParam(required = false, description = "Session id") String sessionId,
    @ToolParam(required = false, description = "Topic key for keyed supersession") String topicKey,
    @ToolParam(required = false, description = "Opaque payload string") String payload,
    @ToolParam(required = false, description = "Profile name override") String profile) {
    return guarded(() -> client.remember(profile(profile), new RememberRequest(type, content, sessionId, topicKey, payload)));
  }

  @Tool(name = "list", description = "List stored memories, optionally filtered by type and session.")
  public String list(
    @ToolParam(required = false, description = "Filter by memory type") String type,
    @ToolParam(required = false, description = "Filter by session id") String session,
    @ToolParam(required = false, description = "Profile name override") String profile) {
    return guarded(() -> client.list(profile(profile), type, session));
  }

  @Tool(name = "forget", description = "Forget (delete) a memory by its id.")
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
