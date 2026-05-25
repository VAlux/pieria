package dev.alvo.pieria.cli.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
      tmp.resolve("home").resolve("harness"),
      profile,
      "http://127.0.0.1:8077",
      false,
      new PrintStream(new ByteArrayOutputStream())
    );
  }

  @Test
  void installWritesMcpHooksAndExtractsScripts(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "myproj");
    installer.install(ctx);

    ObjectNode mcp = json.load(installer.mcpFile(ctx));
    JsonNode server = mcp.path("mcpServers").path("pieria");
    assertThat(server.path("command").asString()).isEqualTo("/opt/pieria/bin/pieria-gateway");
    assertThat(server.path("env").path("PIERIA_DAEMON_URL").asString()).isEqualTo("http://127.0.0.1:8077");
    assertThat(server.path("env").path("PIERIA_PROFILE").asString()).isEqualTo("myproj");

    ObjectNode settings = json.load(installer.settingsFile(ctx));
    String stopCmd = settings.path("hooks").path("Stop").get(0).path("hooks").get(0).path("command").asString();
    assertThat(stopCmd).contains("claude-code").contains("stop.sh").contains(tmp.toString());

    Path script = ctx.harnessDir().resolve("claude-code").resolve("session-start.sh");
    assertThat(Files.exists(script)).isTrue();
    assertThat(Files.exists(ctx.harnessDir().resolve("profile-name.sh"))).isTrue();
    if (Files.getFileStore(script).supportsFileAttributeView("posix")) {
      assertThat(Files.isExecutable(script)).isTrue();
    }
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
