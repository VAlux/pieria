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

class CodexInstallerTests {

  private final CodexInstaller installer = new CodexInstaller();
  private final TomlConfigMerger toml = new TomlConfigMerger();
  private final JsonConfigMerger json = new JsonConfigMerger();

  private WiringContext ctx(Path tmp, String profile) {
    return new WiringContext(
      Scope.PROJECT,
      tmp.resolve("proj"),
      tmp.resolve("user"),
      "/opt/pieria/bin/pieria-gateway",
      "/opt/pieria/bin/pieria",
      profile,
      "http://127.0.0.1:8077",
      false,
      new Logger()
    );
  }

  @Test
  void installWritesServerToTomlAndHooksToJson(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "p");
    installer.install(ctx);

    ObjectNode config = toml.load(installer.configFile(ctx));
    assertThat(config.path("mcp_servers").path("pieria").path("command").asString())
      .isEqualTo("/opt/pieria/bin/pieria-gateway");
    assertThat(config.path("mcp_servers").path("pieria").path("env").path("PIERIA_DAEMON_URL").asString())
      .isEqualTo("http://127.0.0.1:8077");
    assertThat(config.has("hooks")).isFalse();

    ObjectNode hooks = json.load(installer.hooksFile(ctx));
    assertThat(handlerCommand(hooks, "SessionStart"))
      .isEqualTo("/opt/pieria/bin/pieria hook codex session-start");
    assertThat(handlerCommand(hooks, "Stop"))
      .isEqualTo("/opt/pieria/bin/pieria hook codex stop");
    assertThat(hooks.path("hooks").path("Stop").path(0).path("hooks").path(0).path("type").asString())
      .isEqualTo("command");
    assertThat(hooks.path("hooks").path("Stop").path(0).path("hooks").path(0).path("timeout").asInt())
      .isEqualTo(30);
  }

  @Test
  void hooksInvokeTheBinaryNotAShellScript(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "myproj");
    installer.install(ctx);

    String hooks = Files.readString(installer.hooksFile(ctx));
    assertThat(hooks).contains("/opt/pieria/bin/pieria hook codex stop");
    assertThat(hooks).contains("/opt/pieria/bin/pieria hook codex session-start");
    assertThat(hooks).doesNotContain(".sh");
  }

  @Test
  void installIsIdempotent(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "p");
    installer.install(ctx);
    installer.install(ctx);
    ObjectNode root = json.load(installer.hooksFile(ctx));
    assertThat(root.path("hooks").path("SessionStart").size()).isEqualTo(1);
    assertThat(root.path("hooks").path("Stop").size()).isEqualTo(1);
  }

  @Test
  void installsSlashCommandPrompts(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "p");
    installer.install(ctx);

    Path remember = installer.commandsDir(ctx).resolve("pieria-remember.md");
    assertThat(Files.exists(remember)).isTrue();
    // Codex prompts are model-mediated: they reference the MCP tool, not a shell script.
    assertThat(Files.readString(remember)).contains("mcp__pieria__remember");

    installer.uninstall(ctx);
    assertThat(Files.exists(remember)).isFalse();
  }

  @Test
  void uninstallRemovesOnlyPieria(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "p");
    Path config = installer.configFile(ctx);
    Files.createDirectories(config.getParent());
    Files.writeString(config, "model = \"gpt-5\"\n[mcp_servers.other]\ncommand = \"other-mcp\"\n");

    Path hooksFile = installer.hooksFile(ctx);
    Files.writeString(hooksFile, """
      {
        "description": "keep me",
        "hooks": {
          "Stop": [
            {"hooks": [{"type": "command", "command": "echo other"}]}
          ]
        }
      }
      """);

    installer.install(ctx);
    assertThat(installer.isInstalled(ctx)).isTrue();
    installer.uninstall(ctx);
    assertThat(installer.isInstalled(ctx)).isFalse();

    ObjectNode configRoot = toml.load(config);
    assertThat(configRoot.path("model").asString()).isEqualTo("gpt-5");
    assertThat(configRoot.path("mcp_servers").path("other").path("command").asString())
      .isEqualTo("other-mcp");

    ObjectNode hooksRoot = json.load(hooksFile);
    assertThat(hooksRoot.path("description").asString()).isEqualTo("keep me");
    ArrayNode stop = (ArrayNode) hooksRoot.path("hooks").path("Stop");
    assertThat(stop.size()).isEqualTo(1);
    assertThat(stop.path(0).path("hooks").path(0).path("command").asString()).isEqualTo("echo other");
    assertThat(hooksRoot.path("hooks").has("SessionStart")).isFalse();
  }

  @Test
  void reinstallPreservesUnrelatedHandlerInTheSameGroup(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "p");
    installer.install(ctx);

    ObjectNode root = json.load(installer.hooksFile(ctx));
    ArrayNode handlers = (ArrayNode) root.path("hooks").path("Stop").path(0).path("hooks");
    ObjectNode unrelated = json.newObject();
    unrelated.put("type", "command");
    unrelated.put("command", "echo other");
    handlers.add(unrelated);
    json.save(installer.hooksFile(ctx), root, false, ctx.log());

    installer.install(ctx);

    ObjectNode reloaded = json.load(installer.hooksFile(ctx));
    ArrayNode groups = (ArrayNode) reloaded.path("hooks").path("Stop");
    assertThat(groups.size()).isEqualTo(2);
    assertThat(groups.findValues("command").stream().map(JsonNode::asString))
      .containsExactlyInAnyOrder("echo other", "/opt/pieria/bin/pieria hook codex stop");
  }

  @Test
  void uninstallPreservesUnrelatedHandlerInTheSameGroup(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "p");
    installer.install(ctx);

    ObjectNode root = json.load(installer.hooksFile(ctx));
    ArrayNode handlers = (ArrayNode) root.path("hooks").path("Stop").path(0).path("hooks");
    ObjectNode unrelated = json.newObject();
    unrelated.put("type", "command");
    unrelated.put("command", "echo other");
    handlers.add(unrelated);
    json.save(installer.hooksFile(ctx), root, false, ctx.log());

    installer.uninstall(ctx);

    ObjectNode reloaded = json.load(installer.hooksFile(ctx));
    ArrayNode groups = (ArrayNode) reloaded.path("hooks").path("Stop");
    assertThat(groups.size()).isEqualTo(1);
    assertThat(groups.findValues("command").stream().map(JsonNode::asString))
      .containsExactly("echo other");
  }

  private String handlerCommand(ObjectNode root, String event) {
    return root.path("hooks").path(event).path(0).path("hooks").path(0).path("command").asString();
  }
}
