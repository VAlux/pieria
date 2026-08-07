package dev.alvo.pieria.cli.modules.harness;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires the Codex CLI: an {@code [mcp_servers.pieria]} table in {@code config.toml}, plus
 * {@code Stop} ingestion and {@code SessionStart} recall entries in {@code hooks.json}. Project
 * scope writes under {@code ./.codex/}; {@code --user} writes under {@code ~/.codex/}.
 *
 * <p>VERIFY against current Codex CLI docs (as of 2026-07): the {@code [mcp_servers.*]} table,
 * the {@code hooks.json} structure, event names, and stdin payload. Codex command hooks are recent
 * and command-only.
 */
public final class CodexInstaller implements HarnessInstaller {

  /**
   * Codex hook event → {@code pieria hook codex} subcommand.
   */
  private static final Map<String, String> HOOK_EVENTS = new LinkedHashMap<>() {{
    put("SessionStart", "session-start");
    put("Stop", "stop");
  }};

  /**
   * User-triggered slash commands, installed under {@code .codex/prompts/}. Codex prompts are
   * message templates (no shell execution), so these are model-mediated: they instruct the model to
   * call the MCP {@code remember} tool. No {@code <PIERIA_BIN>} placeholder to
   * substitute.
   */
  private static final Map<String, String> COMMANDS = new LinkedHashMap<>() {{
    put("pieria-remember.md", "harness/codex/commands/pieria-remember.md");
  }};

  private final TomlConfigMerger toml = new TomlConfigMerger();
  private final JsonConfigMerger json = new JsonConfigMerger();
  private final CommandAssetWriter commands = new CommandAssetWriter();

  /** Remove Pieria handlers while preserving unrelated handlers in the same matcher group. */
  private static boolean removePieriaEntries(ArrayNode groups) {
    boolean changed = false;
    for (int groupIndex = groups.size() - 1; groupIndex >= 0; groupIndex--) {
      JsonNode group = groups.get(groupIndex);
      JsonNode handlers = group.get("hooks");
      if (!(handlers instanceof ArrayNode handlerArray)) {
        continue;
      }
      for (int handlerIndex = handlerArray.size() - 1; handlerIndex >= 0; handlerIndex--) {
        JsonNode command = handlerArray.get(handlerIndex).get("command");
        if (command != null && isPieriaHookCommand(command.asString())) {
          handlerArray.remove(handlerIndex);
          changed = true;
        }
      }
      if (handlerArray.isEmpty()) {
        groups.remove(groupIndex);
      }
    }
    return changed;
  }

  /** Whether a hook command is one of Pieria's, i.e. {@code <pieria> hook codex <event>}. */
  private static boolean isPieriaHookCommand(String command) {
    return command != null && command.contains("hook codex");
  }

  @Override
  public String id() {
    return "codex";
  }

  Path configFile(WiringContext ctx) {
    return ctx.scope() == Scope.USER
      ? ctx.userHome().resolve(".codex").resolve("config.toml")
      : ctx.projectDir().resolve(".codex").resolve("config.toml");
  }

  Path hooksFile(WiringContext ctx) {
    return ctx.scope() == Scope.USER
      ? ctx.userHome().resolve(".codex").resolve("hooks.json")
      : ctx.projectDir().resolve(".codex").resolve("hooks.json");
  }

  Path commandsDir(WiringContext ctx) {
    return ctx.scope() == Scope.USER
      ? ctx.userHome().resolve(".codex").resolve("prompts")
      : ctx.projectDir().resolve(".codex").resolve("prompts");
  }

  @Override
  public void install(WiringContext ctx) throws IOException {
    Path config = configFile(ctx);
    ObjectNode root = toml.load(config);

    // [mcp_servers.pieria]
    ObjectNode servers = toml.childObject(root, "mcp_servers");
    servers.set("pieria", mcpServerNode(ctx));
    toml.save(config, root, ctx.dryRun(), ctx.log());

    // hooks.json — replace any existing Pieria handlers, then append one group per event.
    Path hooksFile = hooksFile(ctx);
    ObjectNode hooksRoot = json.load(hooksFile);
    ObjectNode hooks = json.childObject(hooksRoot, "hooks");
    HOOK_EVENTS.forEach((event, subcommand) -> {
      ArrayNode groups = json.childArray(hooks, event);
      removePieriaEntries(groups);
      groups.add(hookGroup(ctx, subcommand));
    });
    json.save(hooksFile, hooksRoot, ctx.dryRun(), ctx.log());

    // User-triggered slash commands (Codex prompts). No placeholder substitution needed: these
    // templates are model-mediated and contain no shell invocation.
    Path cmdDir = commandsDir(ctx);
    for (Map.Entry<String, String> command : COMMANDS.entrySet()) {
      commands.write(command.getValue(), cmdDir.resolve(command.getKey()), Map.of(), ctx.dryRun(), ctx.log());
    }
  }

  @Override
  public void uninstall(WiringContext ctx) throws IOException {
    Path config = configFile(ctx);
    ObjectNode root = toml.load(config);
    boolean changed = false;

    JsonNode servers = root.get("mcp_servers");
    if (servers instanceof ObjectNode serversObject && serversObject.has("pieria")) {
      serversObject.remove("pieria");
      changed = true;
    }

    if (changed) {
      toml.save(config, root, ctx.dryRun(), ctx.log());
    }

    Path hooksFile = hooksFile(ctx);
    ObjectNode hooksRoot = json.load(hooksFile);
    JsonNode hooks = hooksRoot.get("hooks");
    if (hooks instanceof ObjectNode hooksObject) {
      boolean hooksChanged = false;
      for (String event : HOOK_EVENTS.keySet()) {
        JsonNode groupsNode = hooksObject.get(event);
        if (groupsNode instanceof ArrayNode groups) {
          hooksChanged = removePieriaEntries(groups) || hooksChanged;
          if (groups.isEmpty()) {
            hooksObject.remove(event);
          }
        }
      }
      if (hooksObject.isEmpty()) {
        hooksRoot.remove("hooks");
      }
      if (hooksChanged) {
        json.save(hooksFile, hooksRoot, ctx.dryRun(), ctx.log());
      }
    }

    Path cmdDir = commandsDir(ctx);
    for (String file : COMMANDS.keySet()) {
      commands.delete(cmdDir.resolve(file), ctx.dryRun(), ctx.log());
    }
  }

  @Override
  public boolean isInstalled(WiringContext ctx) throws IOException {
    ObjectNode root = toml.load(configFile(ctx));
    JsonNode servers = root.get("mcp_servers");
    return servers instanceof ObjectNode object && object.has("pieria");
  }

  private ObjectNode mcpServerNode(WiringContext ctx) {
    ObjectNode server = toml.newObject();
    server.put("command", ctx.gatewayCommand());
    ObjectNode env = toml.newObject();
    env.put("PIERIA_DAEMON_URL", ctx.daemonUrl());
    env.put("PIERIA_HARNESS", "codex");
    if (ctx.hasProfile()) {
      env.put("PIERIA_PROFILE", ctx.profile());
    }
    server.set("env", env);
    return server;
  }

  private ObjectNode hookGroup(WiringContext ctx, String subcommand) {
    ObjectNode group = json.newObject();
    ArrayNode handlers = json.childArray(group, "hooks");
    ObjectNode handler = json.newObject();
    handler.put("type", "command");
    handler.put("command", HookCommandLine.of(ctx.cliCommand(), "hook", "codex", subcommand));
    if (subcommand.equals("stop")) {
      handler.put("timeout", 30);
    }
    handlers.add(handler);
    return group;
  }
}
