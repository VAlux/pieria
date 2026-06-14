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
   * Codex hook event -> embedded script under {@code harness/codex/}.
   */
  private static final Map<String, String> HOOK_SCRIPTS = new LinkedHashMap<>() {{
    put("Stop", "stop.sh");
    put("SessionStart", "session-start.sh");
  }};

  private final TomlConfigMerger toml = new TomlConfigMerger();
  private final HookAssetWriter assets = new HookAssetWriter();

  private static void removePieriaEntries(ArrayNode hooks) {
    for (int i = hooks.size() - 1; i >= 0; i--) {
      JsonNode command = hooks.get(i).get("command");
      if (command != null && isPieriaHookCommand(command.asString())) {
        hooks.remove(i);
      }
    }
  }

  private static boolean isPieriaHookCommand(String command) {
    for (String script : HOOK_SCRIPTS.values()) {
      if (command.contains("codex") && command.contains(script)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String id() {
    return "codex";
  }

  @Override
  public List<String> requiredScriptResources() {
    List<String> resources = new ArrayList<>();
    for (String script : HOOK_SCRIPTS.values()) {
      resources.add("harness/codex/" + script);
    }
    return resources;
  }

  Path configFile(WiringContext ctx) {
    return ctx.scope() == Scope.USER
      ? ctx.userHome().resolve(".codex").resolve("config.toml")
      : ctx.projectDir().resolve(".codex").resolve("config.toml");
  }

  @Override
  public void install(WiringContext ctx) throws IOException {
    assets.extract(ctx.harnessDir(), requiredScriptResources(), ctx.dryRun(), ctx.log());

    Path config = configFile(ctx);
    ObjectNode root = toml.load(config);

    // [mcp_servers.pieria]
    ObjectNode servers = toml.childObject(root, "mcp_servers");
    servers.set("pieria", mcpServerNode(ctx));

    // [[hooks]] — replace any existing Pieria entries, then append ours.
    ArrayNode hooks = toml.childArray(root, "hooks");
    removePieriaEntries(hooks);
    HOOK_SCRIPTS.forEach((event, script) -> hooks.add(hookEntry(ctx, event, script)));

    toml.save(config, root, ctx.dryRun(), ctx.log());
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
    if (ctx.hasProfile()) {
      env.put("PIERIA_PROFILE", ctx.profile());
    }
    server.set("env", env);
    return server;
  }

  private ObjectNode hookEntry(WiringContext ctx, String event, String script) {
    ObjectNode entry = toml.newObject();
    entry.put("event", event);
    entry.put("command", "sh " + ctx.harnessDir().resolve("codex").resolve(script));
    return entry;
  }
}
