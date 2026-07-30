package dev.alvo.pieria.cli.modules.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.alvo.pieria.cli.log.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCodeInstallerTests {

  private final ClaudeCodeInstaller installer = new ClaudeCodeInstaller();
  private final JsonConfigMerger json = new JsonConfigMerger();

  private WiringContext ctx(Path tmp, String profile) {
    return new WiringContext(
      Scope.PROJECT,
      tmp.resolve("proj"),
      tmp.resolve("user"),
      "/opt/pieria/bin/pieria-gateway",
      "/opt/pieria/bin/pieria",
      tmp.resolve("home").resolve("harness"),
      profile,
      "http://127.0.0.1:8077",
      false,
      new Logger()
    );
  }

  @Test
  void installWritesMcpAndBinaryHookCommands(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "myproj");
    installer.install(ctx);

    ObjectNode mcp = json.load(installer.mcpFile(ctx));
    JsonNode server = mcp.path("mcpServers").path("pieria");
    assertThat(server.path("command").asString()).isEqualTo("/opt/pieria/bin/pieria-gateway");
    assertThat(server.path("env").path("PIERIA_DAEMON_URL").asString()).isEqualTo("http://127.0.0.1:8077");
    assertThat(server.path("env").path("PIERIA_PROFILE").asString()).isEqualTo("myproj");

    ObjectNode settings = json.load(installer.settingsFile(ctx));
    assertThat(hookCommand(settings, "Stop")).isEqualTo("/opt/pieria/bin/pieria hook claude-code stop");
    assertThat(hookCommand(settings, "SessionStart"))
      .isEqualTo("/opt/pieria/bin/pieria hook claude-code session-start");
    assertThat(hookCommand(settings, "PreCompact"))
      .isEqualTo("/opt/pieria/bin/pieria hook claude-code pre-compact");
    assertThat(hookCommand(settings, "SessionEnd"))
      .isEqualTo("/opt/pieria/bin/pieria hook claude-code session-end");
  }

  private String hookCommand(ObjectNode settings, String event) {
    return settings.path("hooks").path(event).get(0).path("hooks").get(0).path("command").asString();
  }

  @Test
  void quotesTheExecutableWhenTheInstallPathHasSpaces(@TempDir Path tmp) throws IOException {
    WiringContext ctx = new WiringContext(
      Scope.PROJECT, tmp.resolve("proj"), tmp.resolve("user"),
      "C:\\Program Files\\Pieria\\bin\\pieria-gateway.exe",
      "C:\\Program Files\\Pieria\\bin\\pieria.exe",
      tmp.resolve("home").resolve("harness"),
      "myproj", "http://127.0.0.1:8077", false, new Logger());

    installer.install(ctx);

    ObjectNode settings = json.load(installer.settingsFile(ctx));
    assertThat(hookCommand(settings, "Stop"))
      .isEqualTo("\"C:\\Program Files\\Pieria\\bin\\pieria.exe\" hook claude-code stop");
  }

  @Test
  void reinstallIsIdempotentAndLeavesOneEntryPerEvent(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "myproj");

    installer.install(ctx);
    installer.install(ctx);

    ObjectNode settings = json.load(installer.settingsFile(ctx));
    assertThat((ArrayNode) settings.path("hooks").path("Stop")).hasSize(1);
    assertThat(hookCommand(settings, "Stop")).isEqualTo("/opt/pieria/bin/pieria hook claude-code stop");
  }

  @Test
  void uninstallRemovesPieriaEntriesAndLeavesForeignOnesAlone(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "myproj");
    Path settingsFile = installer.settingsFile(ctx);
    Files.createDirectories(settingsFile.getParent());
    Files.writeString(settingsFile, """
      {
        "hooks": {
          "Stop": [
            {"matcher": "", "hooks": [{"type": "command", "command": "/usr/local/bin/other-tool report"}]}
          ]
        }
      }
      """);

    installer.install(ctx);
    installer.uninstall(ctx);

    ObjectNode settings = json.load(settingsFile);
    ArrayNode stop = (ArrayNode) settings.path("hooks").path("Stop");
    assertThat(stop).hasSize(1);
    assertThat(stop.get(0).path("hooks").get(0).path("command").asString())
      .isEqualTo("/usr/local/bin/other-tool report");
  }

  @Test
  void doesNotWirePerPromptHook(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "myproj");
    installer.install(ctx);

    ObjectNode settings = json.load(installer.settingsFile(ctx));
    assertThat(settings.path("hooks").has("UserPromptSubmit")).isFalse();
  }

  @Test
  void installStripsLegacyUserPromptSubmitHook(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "myproj");
    // Seed a prior install that had the per-prompt hook (current binary form) plus an unrelated
    // user hook. UserPromptSubmit is no longer installed by Pieria at all (see
    // doesNotWirePerPromptHook), so any leftover Pieria entry under this event is stale and pruned.
    Path settingsFile = installer.settingsFile(ctx);
    Files.createDirectories(settingsFile.getParent());
    Files.writeString(settingsFile, "{\"hooks\":{\"UserPromptSubmit\":["
      + "{\"matcher\":\"\",\"hooks\":[{\"type\":\"command\",\"command\":\"/opt/pieria/bin/pieria hook claude-code user-prompt-submit\"}]},"
      + "{\"matcher\":\"\",\"hooks\":[{\"type\":\"command\",\"command\":\"echo keep-me\"}]}]}}");

    installer.install(ctx);

    ObjectNode settings = json.load(settingsFile);
    ArrayNode ups = (ArrayNode) settings.path("hooks").path("UserPromptSubmit");
    // Pieria's legacy entry removed; the unrelated hook preserved.
    assertThat(ups.size()).isEqualTo(1);
    assertThat(ups.get(0).path("hooks").get(0).path("command").asString()).isEqualTo("echo keep-me");
  }

  @Test
  void wiresSessionEndHookCommand(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "myproj");
    installer.install(ctx);

    ObjectNode settings = json.load(installer.settingsFile(ctx));
    assertThat(hookCommand(settings, "SessionEnd"))
      .isEqualTo("/opt/pieria/bin/pieria hook claude-code session-end");
  }

  @Test
  void installsSlashCommandsWithCliBinarySubstituted(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "myproj");
    installer.install(ctx);

    Path remember = installer.commandsDir(ctx).resolve("pieria-remember.md");
    Path recall = installer.commandsDir(ctx).resolve("pieria-recall.md");
    assertThat(Files.exists(remember)).isTrue();
    assertThat(Files.exists(recall)).isTrue();

    String body = Files.readString(remember);
    assertThat(body)
      .contains("hook remember")
      .contains(ctx.cliCommand())
      .doesNotContain("<PIERIA_BIN>");

    // Uninstall removes the command files.
    installer.uninstall(ctx);
    assertThat(Files.exists(remember)).isFalse();
    assertThat(Files.exists(recall)).isFalse();
  }

  @Test
  void omitsProfileEnvWhenNotProvided(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, null);
    installer.install(ctx);
    ObjectNode mcp = json.load(installer.mcpFile(ctx));
    assertThat(mcp.path("mcpServers").path("pieria").path("env").has("PIERIA_PROFILE")).isFalse();
  }

  @Test
  void installIsIdempotent(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "p");
    installer.install(ctx);
    installer.install(ctx);
    ObjectNode settings = json.load(installer.settingsFile(ctx));
    ArrayNode sessionStart = (ArrayNode) settings.path("hooks").path("SessionStart");
    assertThat(sessionStart.size()).isEqualTo(1);
  }

  @Test
  void preservesUnrelatedConfigAndUninstallRemovesOnlyPieria(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "p");
    // Seed unrelated entries.
    Path mcpFile = installer.mcpFile(ctx);
    Files.createDirectories(mcpFile.getParent());
    Files.writeString(mcpFile, "{\"mcpServers\":{\"other\":{\"command\":\"keepme\"}}}");
    Path settingsFile = installer.settingsFile(ctx);
    Files.createDirectories(settingsFile.getParent());
    Files.writeString(settingsFile,
      "{\"hooks\":{\"Stop\":[{\"matcher\":\"\",\"hooks\":[{\"type\":\"command\",\"command\":\"echo other\"}]}]}}");

    installer.install(ctx);
    assertThat(installer.isInstalled(ctx)).isTrue();

    installer.uninstall(ctx);
    assertThat(installer.isInstalled(ctx)).isFalse();

    ObjectNode mcp = json.load(mcpFile);
    assertThat(mcp.path("mcpServers").path("other").path("command").asString()).isEqualTo("keepme");
    assertThat(mcp.path("mcpServers").has("pieria")).isFalse();

    ObjectNode settings = json.load(settingsFile);
    ArrayNode stop = (ArrayNode) settings.path("hooks").path("Stop");
    assertThat(stop.size()).isEqualTo(1);
    assertThat(stop.get(0).path("hooks").get(0).path("command").asString()).isEqualTo("echo other");
  }
}
