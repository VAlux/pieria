package dev.alvo.pieria.cli.modules.harness;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires the Codex CLI: an {@code [mcp_servers.pieria]} table plus {@code [[hooks]]} entries
 * ({@code Stop} ingestion, {@code SessionStart} recall) in {@code config.toml}.
 * Project scope writes {@code ./.codex/config.toml}; {@code --user} writes {@code ~/.codex/config.toml}.
 *
 * <p>VERIFY against current Codex CLI docs (as of 2026-05): the {@code [mcp_servers.*]} table,
 * the {@code [[hooks]]} structure, the event names, and the transcript env var. Codex command
 * hooks are recent and command-only.
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
   * call the MCP {@code remember}/{@code recall} tools. No {@code <PIERIA_BIN>} placeholder to
   * substitute.
   */
  private static final Map<String, String> COMMANDS = new LinkedHashMap<>() {{
    put("pieria-remember.md", "harness/codex/commands/pieria-remember.md");
    put("pieria-recall.md", "harness/codex/commands/pieria-recall.md");
  }};

  private final TomlConfigMerger toml = new TomlConfigMerger();
  private final CommandAssetWriter commands = new CommandAssetWriter();

  private static void removePieriaEntries(ArrayNode hooks) {
    for (int i = hooks.size() - 1; i >= 0; i--) {
      JsonNode command = hooks.get(i).get("command");
      if (command != null && isPieriaHookCommand(command.asString())) {
        hooks.remove(i);
      }
    }
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

    // [[hooks]] — replace any existing Pieria entries, then append ours.
    ArrayNode hooks = toml.childArray(root, "hooks");
    removePieriaEntries(hooks);
    HOOK_EVENTS.forEach((event, subcommand) -> hooks.add(hookEntry(ctx, event, subcommand)));

    toml.save(config, root, ctx.dryRun(), ctx.log());

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

    JsonNode hooks = root.get("hooks");
    if (hooks instanceof ArrayNode hooksArray) {
      int before = hooksArray.size();
      removePieriaEntries(hooksArray);
      changed = changed || hooksArray.size() != before;
    }

    if (changed) {
      toml.save(config, root, ctx.dryRun(), ctx.log());
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

  private ObjectNode hookEntry(WiringContext ctx, String event, String subcommand) {
    ObjectNode entry = toml.newObject();
    entry.put("event", event);
    entry.put("command", HookCommandLine.of(ctx.cliCommand(), "hook", "codex", subcommand));
    return entry;
  }
}
