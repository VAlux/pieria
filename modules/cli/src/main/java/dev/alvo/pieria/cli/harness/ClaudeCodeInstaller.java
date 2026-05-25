package dev.alvo.pieria.cli.harness;

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
 * Wires Claude Code: an MCP server in {@code .mcp.json} plus {@code SessionStart}/{@code PreCompact}/
 * {@code Stop} hooks in {@code settings.json} (SPEC §10.4). Project scope writes to the repo;
 * {@code --user} writes under {@code ~/.claude/}.
 *
 * <p>VERIFY against current Claude Code docs (as of 2026-05): hook event names, the {@code .mcp.json}
 * shape, and the user-level MCP config location.
 */
public final class ClaudeCodeInstaller implements HarnessInstaller {

  /** Claude Code hook event -> embedded script under {@code harness/claude-code/}. */
  private static final Map<String, String> HOOK_SCRIPTS = new LinkedHashMap<>() {{
    put("SessionStart", "session-start.sh");
    put("PreCompact", "pre-compact.sh");
    put("Stop", "stop.sh");
  }};

  private final JsonConfigMerger json = new JsonConfigMerger();
  private final HookAssetWriter assets = new HookAssetWriter();

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

  @Override
  public void install(WiringContext ctx) throws IOException {
    assets.extract(ctx.harnessDir(), requiredScriptResources(), ctx.dryRun(), ctx.out());

    // 1. MCP server registration.
    Path mcp = mcpFile(ctx);
    ObjectNode mcpRoot = json.load(mcp);
    ObjectNode servers = json.childObject(mcpRoot, "mcpServers");
    servers.set("pieria", mcpServerNode(ctx));
    json.save(mcp, mcpRoot, ctx.dryRun(), ctx.out());

    // 2. Lifecycle hooks.
    Path settings = settingsFile(ctx);
    ObjectNode settingsRoot = json.load(settings);
    ObjectNode hooks = json.childObject(settingsRoot, "hooks");
    HOOK_SCRIPTS.forEach((event, script) -> {
      ArrayNode eventArray = json.childArray(hooks, event);
      removePieriaEntries(eventArray);
      eventArray.add(hookGroup(ctx, script));
    });
    json.save(settings, settingsRoot, ctx.dryRun(), ctx.out());
  }

  @Override
  public void uninstall(WiringContext ctx) throws IOException {
    Path mcp = mcpFile(ctx);
    ObjectNode mcpRoot = json.load(mcp);
    JsonNode servers = mcpRoot.get("mcpServers");
    if (servers instanceof ObjectNode serversObject && serversObject.has("pieria")) {
      serversObject.remove("pieria");
      json.save(mcp, mcpRoot, ctx.dryRun(), ctx.out());
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
      json.save(settings, settingsRoot, ctx.dryRun(), ctx.out());
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
    if (ctx.hasProfile()) {
      env.put("PIERIA_PROFILE", ctx.profile());
    }
    server.set("env", env);
    return server;
  }

  /** A Claude Code hook group: {@code { "matcher": "", "hooks": [ { "type":"command", "command":... } ] }}. */
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

  private static String hookCommand(WiringContext ctx, String script) {
    return "sh " + ctx.harnessDir().resolve("claude-code").resolve(script);
  }

  /** Remove hook groups that contain a Pieria claude-code script command. */
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
    for (String script : HOOK_SCRIPTS.values()) {
      if (command.contains("claude-code") && command.contains(script)) {
        return true;
      }
    }
    return false;
  }
}
