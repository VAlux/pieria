package dev.alvo.pieria.cli.modules.harness;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
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
   * Claude Code hook event → {@code pieria hook claude-code} subcommand. SessionStart primes context
   * with prior memories; PreCompact/Stop/SessionEnd capture the transcript.
   */
  private static final Map<String, String> HOOK_EVENTS = new LinkedHashMap<>() {{
    put("SessionStart", "session-start");
    put("PreCompact", "pre-compact");
    put("Stop", "stop");
    put("SessionEnd", "session-end");
  }};

  /** Hook events Pieria used to install and no longer does. */
  private static final List<String> LEGACY_EVENTS = List.of("UserPromptSubmit");

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
  private final CommandAssetWriter commands = new CommandAssetWriter();

  private static String hookCommand(WiringContext ctx, String subcommand) {
    return HookCommandLine.of(ctx.cliCommand(), "hook", "claude-code", subcommand);
  }

  /**
   * Strip any leftover Pieria groups for hooks we no longer install (e.g. the removed per-prompt
   * {@code UserPromptSubmit} recall), pruning the event key if it becomes empty.
   */
  private static void stripLegacyHooks(ObjectNode hooks) {
    for (String event : LEGACY_EVENTS) {
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

  /** Whether a hook command is one of Pieria's, i.e. {@code <pieria> hook claude-code <event>}. */
  private static boolean isPieriaHookCommand(String command) {
    return command != null && command.contains("hook claude-code");
  }

  @Override
  public String id() {
    return "claude-code";
  }

  @Override
  public List<String> requiredScriptResources() {
    return List.of();
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
    HOOK_EVENTS.forEach((event, subcommand) -> {
      ArrayNode eventArray = json.childArray(hooks, event);
      removePieriaEntries(eventArray);
      eventArray.add(hookGroup(ctx, subcommand));
    });
    stripLegacyHooks(hooks);
    json.save(settings, settingsRoot, ctx.dryRun(), ctx.log());

    // 3. User-triggered slash commands.
    Path cmdDir = commandsDir(ctx);
    Map<String, String> subs = Map.of("<PIERIA_BIN>", ctx.cliCommand());
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
      for (String event : HOOK_EVENTS.keySet()) {
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
  private ObjectNode hookGroup(WiringContext ctx, String subcommand) {
    ObjectNode group = json.newObject();
    group.put("matcher", "");
    ArrayNode inner = json.childArray(group, "hooks");
    ObjectNode hook = json.newObject();
    hook.put("type", "command");
    hook.put("command", hookCommand(ctx, subcommand));
    inner.add(hook);
    return group;
  }
}
