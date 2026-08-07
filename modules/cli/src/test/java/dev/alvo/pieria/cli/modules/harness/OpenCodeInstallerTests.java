package dev.alvo.pieria.cli.modules.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import dev.alvo.pieria.cli.log.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeInstallerTests {

  private final OpenCodeInstaller installer = new OpenCodeInstaller();
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
  void installWritesMcpHooksAndCommands(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "myproj");
    installer.install(ctx);

    ObjectNode config = json.load(installer.configFile(ctx));

    // MCP server as a local command array.
    var server = config.path("mcp").path("pieria");
    assertThat(server.path("type").asString()).isEqualTo("local");
    assertThat(server.path("command").get(0).asString()).isEqualTo("/opt/pieria/bin/pieria-gateway");
    assertThat(server.path("env").path("PIERIA_DAEMON_URL").asString()).isEqualTo("http://127.0.0.1:8077");
    assertThat(server.path("env").path("PIERIA_PROFILE").asString()).isEqualTo("myproj");

    // Experimental lifecycle hooks.
    assertThat(config.path("experimental").path("session").path("compacting").path("plugin").asString())
      .isEqualTo("/opt/pieria/bin/pieria hook opencode ingest");
    assertThat(config.path("experimental").path("chat").path("system").path("transform").asString())
      .isEqualTo("/opt/pieria/bin/pieria hook opencode recall-transform");

    // Slash commands written with the binary substituted.
    Path remember = installer.commandsDir(ctx).resolve("pieria-remember.md");
    assertThat(Files.exists(remember)).isTrue();
    String body = Files.readString(remember);
    assertThat(body).contains("hook remember").doesNotContain("<PIERIA_BIN>");

    assertThat(installer.isInstalled(ctx)).isTrue();
  }

  @Test
  void experimentalHooksInvokeTheBinaryNotAShellScript(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "myproj");
    installer.install(ctx);

    ObjectNode config = json.load(installer.configFile(ctx));
    String compacting = config.path("experimental").path("session")
      .path("compacting").path("plugin").asString();
    String transform = config.path("experimental").path("chat").path("system").path("transform").asString();

    assertThat(compacting).isEqualTo("/opt/pieria/bin/pieria hook opencode ingest");
    assertThat(transform).isEqualTo("/opt/pieria/bin/pieria hook opencode recall-transform");
  }

  @Test
  void installIsIdempotent(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "p");
    installer.install(ctx);
    installer.install(ctx);

    ObjectNode config = json.load(installer.configFile(ctx));
    // Single pieria server, single transform hook (string field, not accumulating).
    assertThat(config.path("mcp").path("pieria").path("type").asString()).isEqualTo("local");
    assertThat(config.path("experimental").path("chat").path("system").path("transform").asString())
      .isEqualTo("/opt/pieria/bin/pieria hook opencode recall-transform");
  }

  @Test
  void omitsProfileEnvWhenNotProvided(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, null);
    installer.install(ctx);
    ObjectNode config = json.load(installer.configFile(ctx));
    assertThat(config.path("mcp").path("pieria").path("env").has("PIERIA_PROFILE")).isFalse();
  }

  @Test
  void preservesUnrelatedConfigAndUninstallRemovesOnlyPieria(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "p");
    Path config = installer.configFile(ctx);
    Files.createDirectories(config.getParent());
    Files.writeString(config,
      "{\"mcp\":{\"other\":{\"type\":\"local\",\"command\":[\"keepme\"]}},"
        + "\"experimental\":{\"chat\":{\"system\":{\"transform\":\"sh other.sh\"}}}}");

    installer.install(ctx);
    assertThat(installer.isInstalled(ctx)).isTrue();
    // Our install overwrote the transform with ours; that is expected (single Pieria owner of it).

    installer.uninstall(ctx);
    assertThat(installer.isInstalled(ctx)).isFalse();

    ObjectNode root = json.load(config);
    assertThat(root.path("mcp").path("other").path("command").get(0).asString()).isEqualTo("keepme");
    assertThat(root.path("mcp").has("pieria")).isFalse();
    // Our transform hook was removed on uninstall.
    assertThat(root.path("experimental").path("chat").path("system").has("transform")).isFalse();

    // Command files are gone.
    assertThat(Files.exists(installer.commandsDir(ctx).resolve("pieria-remember.md"))).isFalse();
  }

  @Test
  void idIsOpencode() {
    assertThat(installer.id()).isEqualTo("opencode");
  }
}
