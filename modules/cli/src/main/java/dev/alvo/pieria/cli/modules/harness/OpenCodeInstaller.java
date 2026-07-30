package dev.alvo.pieria.cli.modules.harness;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.alvo.pieria.tools.StringKit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires OpenCode: an {@code mcp.pieria} server plus experimental lifecycle hooks in
 * {@code opencode.json}, and user-triggered slash commands under {@code .opencode/command/}.
 * Project scope writes {@code ./opencode.json} and {@code ./.opencode/command/}; {@code --user}
 * writes under {@code ~/.config/opencode/}.
 *
 * <p>Hooks: {@code experimental.session.compacting.plugin} ingests the transcript, and
 * {@code experimental.chat.system.transform} injects recalled context (OpenCode has no
 * {@code SessionStart} event — issue #14808).
 *
 * <p>VERIFY against current OpenCode docs (as of 2026-07): the {@code mcp.*.type}/{@code command}
 * shape, the {@code experimental.*} hook keys and their invocation contract, the command-file
 * directory and {@code !`...`} shell syntax. These experimental surfaces may drift.
 */
public final class OpenCodeInstaller implements HarnessInstaller {

  private static final String INGEST_MARKER = "hook opencode ingest";
  private static final String RECALL_TRANSFORM_MARKER = "hook opencode recall-transform";

  /**
   * User-triggered slash commands installed under {@code .opencode/command/}. OpenCode commands
   * support {@code $ARGUMENTS} and {@code !`...`} shell injection, so these are deterministic —
   * they shell out to the pieria binary rather than relying on the model to call the MCP tool.
   */
  private static final Map<String, String> COMMANDS = new LinkedHashMap<>() {{
    put("pieria-remember.md", "harness/opencode/commands/pieria-remember.md");
    put("pieria-recall.md", "harness/opencode/commands/pieria-recall.md");
  }};

  private final JsonConfigMerger json = new JsonConfigMerger();
  private final CommandAssetWriter commands = new CommandAssetWriter();

  private static ObjectNode childIfObject(ObjectNode parent, String field) {
    if (parent == null) {
      return null;
    }
    return parent.get(field) instanceof ObjectNode object ? object : null;
  }

  /** Remove a string leaf field if its value references one of our hook commands. */
  private static boolean removeIfOurs(ObjectNode parent, String leaf, String marker) {
    if (parent == null) {
      return false;
    }
    JsonNode value = parent.get(leaf);
    if (value != null && value.asString().contains(marker)) {
      parent.remove(leaf);
      return true;
    }
    return false;
  }

  @Override
  public String id() {
    return "opencode";
  }

  /** Config root: project {@code ./opencode.json}; user {@code ~/.config/opencode/opencode.json}. */
  Path configFile(WiringContext ctx) {
    return ctx.scope() == Scope.USER
      ? ctx.userHome().resolve(".config").resolve("opencode").resolve("opencode.json")
      : ctx.projectDir().resolve("opencode.json");
  }

  /** Command dir: project {@code ./.opencode/command/}; user {@code ~/.config/opencode/command/}. */
  Path commandsDir(WiringContext ctx) {
    return ctx.scope() == Scope.USER
      ? ctx.userHome().resolve(".config").resolve("opencode").resolve("command")
      : ctx.projectDir().resolve(".opencode").resolve("command");
  }

  @Override
  public void install(WiringContext ctx) throws IOException {
    Path config = configFile(ctx);
    ObjectNode root = json.load(config);

    // 1. MCP server: mcp.pieria
    ObjectNode mcp = json.childObject(root, "mcp");
    mcp.set("pieria", mcpServerNode(ctx));

    // 2. Experimental lifecycle hooks.
    ObjectNode experimental = json.childObject(root, "experimental");
    ObjectNode compacting = json.childObject(json.childObject(experimental, "session"), "compacting");
    compacting.put("plugin", HookCommandLine.of(ctx.cliCommand(), "hook", "opencode", "ingest"));
    ObjectNode system = json.childObject(json.childObject(experimental, "chat"), "system");
    system.put("transform", HookCommandLine.of(ctx.cliCommand(), "hook", "opencode", "recall-transform"));

    json.save(config, root, ctx.dryRun(), ctx.log());

    // 3. User-triggered slash commands.
    Path cmdDir = commandsDir(ctx);
    Map<String, String> subs = Map.of("<PIERIA_BIN>", StringKit.quoteIfSpaced(ctx.cliCommand()));
    for (Map.Entry<String, String> command : COMMANDS.entrySet()) {
      commands.write(command.getValue(), cmdDir.resolve(command.getKey()), subs, ctx.dryRun(), ctx.log());
    }
  }

  @Override
  public void uninstall(WiringContext ctx) throws IOException {
    Path config = configFile(ctx);
    ObjectNode root = json.load(config);
    boolean changed = false;

    JsonNode mcp = root.get("mcp");
    if (mcp instanceof ObjectNode mcpObject && mcpObject.has("pieria")) {
      mcpObject.remove("pieria");
      changed = true;
    }

    ObjectNode experimental = childIfObject(root, "experimental");
    ObjectNode compacting = childIfObject(childIfObject(experimental, "session"), "compacting");
    changed |= removeIfOurs(compacting, "plugin", INGEST_MARKER);
    ObjectNode system = childIfObject(childIfObject(experimental, "chat"), "system");
    changed |= removeIfOurs(system, "transform", RECALL_TRANSFORM_MARKER);

    if (changed) {
      json.save(config, root, ctx.dryRun(), ctx.log());
    }

    Path cmdDir = commandsDir(ctx);
    for (String file : COMMANDS.keySet()) {
      commands.delete(cmdDir.resolve(file), ctx.dryRun(), ctx.log());
    }
  }

  @Override
  public boolean isInstalled(WiringContext ctx) throws IOException {
    ObjectNode root = json.load(configFile(ctx));
    JsonNode mcp = root.get("mcp");
    return mcp instanceof ObjectNode object && object.has("pieria");
  }

  private ObjectNode mcpServerNode(WiringContext ctx) {
    ObjectNode server = json.newObject();
    server.put("type", "local");
    ArrayNode command = json.newArray();
    command.add(ctx.gatewayCommand());
    server.set("command", command);
    ObjectNode env = json.newObject();
    env.put("PIERIA_DAEMON_URL", ctx.daemonUrl());
    env.put("PIERIA_HARNESS", "opencode");
    if (ctx.hasProfile()) {
      env.put("PIERIA_PROFILE", ctx.profile());
    }
    server.set("env", env);
    return server;
  }
}
