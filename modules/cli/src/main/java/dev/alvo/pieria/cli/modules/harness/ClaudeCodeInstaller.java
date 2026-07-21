package dev.alvo.pieria.cli.modules.harness;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wires Claude Code: an MCP server in {@code .mcp.json}, the {@code SessionStart}/
 * {@code PreCompact}/{@code Stop}/{@code SessionEnd} hooks in {@code settings.json}, and the
 * {@code /pieria-remember} and {@code /pieria-recall} slash commands in {@code .claude/commands/}.
 * Project scope writes to the repo; {@code --user} writes under {@code ~/.claude/}.
 *
 * <p>VERIFY against current Claude Code docs (as of 2026-05): hook event names, the {@code .mcp.json}
 * shape, and the user-level MCP config location.
 */
public final class ClaudeCodeInstaller implements HarnessInstaller {

  /**
   * Claude Code hook event -> embedded script under {@code harness/claude-code/}. {@code SessionStart}
   * primes context with prior memories (via {@code harness/recall.sh}); {@code PreCompact}/{@code Stop}/
   * {@code SessionEnd} ingest the transcript ({@code SessionEnd} captures on /clear, quit, and logout
   * before the conversation is discarded).
   *
   * <p>The per-prompt {@code UserPromptSubmit} auto-recall was removed: it added a recall round-trip
   * to every turn for low-precision, mostly-ambient context. On-demand recall now lives in the
   * SessionStart primer plus the deterministic {@code /pieria-recall} slash command. See
   * {@link #LEGACY_HOOK_SCRIPTS} for cleanup of prior installs.
   */
  private static final Map<String, String> HOOK_SCRIPTS = new LinkedHashMap<>() {{
    put("SessionStart", "session-start.sh");
    put("PreCompact", "pre-compact.sh");
    put("Stop", "stop.sh");
    put("SessionEnd", "session-end.sh");
  }};

  /**
   * Hooks Pieria used to install but no longer does. Install and uninstall both strip these so an
   * upgrade (re-running {@code harness install}) removes the stale entry and its script reference.
   */
  private static final Map<String, String> LEGACY_HOOK_SCRIPTS = new LinkedHashMap<>() {{
    put("UserPromptSubmit", "user-prompt-submit.sh");
  }};

  /**
   * User-triggered slash commands: on-disk file name under {@code .claude/commands/} -> embedded
   * template resource. Deterministic — they shell out to the shared clients rather than relying on
   * the model to call the MCP tool.
   */
  private static final Map<String, String> COMMANDS = new LinkedHashMap<>() {{
    put("pieria-remember.md", "harness/claude-code/commands/pieria-remember.md");
    put("pieria-recall.md", "harness/claude-code/commands/pieria-recall.md");
  }};

  private final JsonConfigMerger json = new JsonConfigMerger();
  private final HookAssetWriter assets = new HookAssetWriter();
  private final CommandAssetWriter commands = new CommandAssetWriter();

  private static String hookCommand(WiringContext ctx, String script) {
    return "sh " + ctx.harnessDir().resolve("claude-code").resolve(script);
  }

  /**
   * Strip any leftover Pieria groups for hooks we no longer install (e.g. the removed per-prompt
   * {@code UserPromptSubmit} recall), pruning the event key if it becomes empty.
   */
  private static void stripLegacyHooks(ObjectNode hooks) {
    for (String event : LEGACY_HOOK_SCRIPTS.keySet()) {
      if (hooks.get(event) instanceof ArrayNode eventArray) {
        removePieriaEntries(eventArray);
        if (eventArray.isEmpty()) {
          hooks.remove(event);
        }
      }
    }
  }

  /**
   * Remove hook groups that contain a Pieria claude-code script command.
   */
  private static void removePieriaEntries(ArrayNode eventArray) {
    for (int i = eventArray.size() - 1; i >= 0; i--) {
      if (isPieriaGroup(eventArray.get(i))) {
        eventArray.remove(i);
      }
    }
  }

  private static boolean isPieriaGroup(JsonNode group) {
    JsonNode inner = group.get("hooks");
    if (!(inner instanceof ArrayNode innerArray)) {
      return false;
    }
    for (JsonNode hook : innerArray) {
      JsonNode command = hook.get("command");
      if (command != null && isPieriaHookCommand(command.asString())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPieriaHookCommand(String command) {
    if (!command.contains("claude-code")) {
      return false;
    }
    for (String script : HOOK_SCRIPTS.values()) {
      if (command.contains(script)) {
        return true;
      }
    }
    for (String script : LEGACY_HOOK_SCRIPTS.values()) {
      if (command.contains(script)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String id() {
    return "claude-code";
  }

  @Override
  public List<String> requiredScriptResources() {
    List<String> resources = new ArrayList<>();
    for (String script : HOOK_SCRIPTS.values()) {
      resources.add("harness/claude-code/" + script);
    }
    return resources;
  }

  Path mcpFile(WiringContext ctx) {
    return ctx.scope() == Scope.USER
      ? ctx.userHome().resolve(".claude").resolve(".mcp.json")
      : ctx.projectDir().resolve(".mcp.json");
  }

  Path settingsFile(WiringContext ctx) {
    return ctx.scope() == Scope.USER
      ? ctx.userHome().resolve(".claude").resolve("settings.json")
      : ctx.projectDir().resolve(".claude").resolve("settings.json");
  }

  Path commandsDir(WiringContext ctx) {
    return ctx.scope() == Scope.USER
      ? ctx.userHome().resolve(".claude").resolve("commands")
      : ctx.projectDir().resolve(".claude").resolve("commands");
  }

  @Override
  public void install(WiringContext ctx) throws IOException {
    assets.extract(ctx.harnessDir(), requiredScriptResources(), ctx.dryRun(), ctx.log());

    // 1. MCP server registration.
    Path mcp = mcpFile(ctx);
    ObjectNode mcpRoot = json.load(mcp);
    ObjectNode servers = json.childObject(mcpRoot, "mcpServers");
    servers.set("pieria", mcpServerNode(ctx));
    json.save(mcp, mcpRoot, ctx.dryRun(), ctx.log());

    // 2. Lifecycle hooks.
    Path settings = settingsFile(ctx);
    ObjectNode settingsRoot = json.load(settings);
    ObjectNode hooks = json.childObject(settingsRoot, "hooks");
    HOOK_SCRIPTS.forEach((event, script) -> {
      ArrayNode eventArray = json.childArray(hooks, event);
      removePieriaEntries(eventArray);
      eventArray.add(hookGroup(ctx, script));
    });
    stripLegacyHooks(hooks);
    json.save(settings, settingsRoot, ctx.dryRun(), ctx.log());

    // 3. User-triggered slash commands.
    Path cmdDir = commandsDir(ctx);
    Map<String, String> subs = Map.of("<PIERIA_HARNESS_DIR>", ctx.harnessDir().toString());
    for (Map.Entry<String, String> command : COMMANDS.entrySet()) {
      commands.write(command.getValue(), cmdDir.resolve(command.getKey()), subs, ctx.dryRun(), ctx.log());
    }
  }

  @Override
  public void uninstall(WiringContext ctx) throws IOException {
    Path mcp = mcpFile(ctx);
    ObjectNode mcpRoot = json.load(mcp);
    JsonNode servers = mcpRoot.get("mcpServers");
    if (servers instanceof ObjectNode serversObject && serversObject.has("pieria")) {
      serversObject.remove("pieria");
      json.save(mcp, mcpRoot, ctx.dryRun(), ctx.log());
    }

    Path settings = settingsFile(ctx);
    ObjectNode settingsRoot = json.load(settings);
    JsonNode hooks = settingsRoot.get("hooks");
    if (hooks instanceof ObjectNode hooksObject) {
      for (String event : HOOK_SCRIPTS.keySet()) {
        JsonNode eventNode = hooksObject.get(event);
        if (eventNode instanceof ArrayNode eventArray) {
          removePieriaEntries(eventArray);
          if (eventArray.isEmpty()) {
            hooksObject.remove(event);
          }
        }
      }
      stripLegacyHooks(hooksObject);
      json.save(settings, settingsRoot, ctx.dryRun(), ctx.log());
    }

    Path cmdDir = commandsDir(ctx);
    for (String file : COMMANDS.keySet()) {
      commands.delete(cmdDir.resolve(file), ctx.dryRun(), ctx.log());
    }
  }

  @Override
  public boolean isInstalled(WiringContext ctx) throws IOException {
    ObjectNode mcpRoot = json.load(mcpFile(ctx));
    JsonNode servers = mcpRoot.get("mcpServers");
    return servers instanceof ObjectNode object && object.has("pieria");
  }

  private ObjectNode mcpServerNode(WiringContext ctx) {
    ObjectNode server = json.newObject();
    server.put("command", ctx.gatewayCommand());
    ObjectNode env = json.newObject();
    env.put("PIERIA_DAEMON_URL", ctx.daemonUrl());
    env.put("PIERIA_HARNESS", "claude-code");
    if (ctx.hasProfile()) {
      env.put("PIERIA_PROFILE", ctx.profile());
    }
    server.set("env", env);
    return server;
  }

  /**
   * A Claude Code hook group: {@code { "matcher": "", "hooks": [ { "type":"command", "command":... } ] }}.
   */
  private ObjectNode hookGroup(WiringContext ctx, String script) {
    ObjectNode group = json.newObject();
    group.put("matcher", "");
    ArrayNode inner = json.childArray(group, "hooks");
    ObjectNode hook = json.newObject();
    hook.put("type", "command");
    hook.put("command", hookCommand(ctx, script));
    inner.add(hook);
    return group;
  }
}
