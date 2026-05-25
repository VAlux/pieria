package dev.alvo.pieria.cli.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodexInstallerTests {

  private final CodexInstaller installer = new CodexInstaller();
  private final TomlConfigMerger toml = new TomlConfigMerger();

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
  void installWritesServerAndHooks(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "p");
    installer.install(ctx);

    ObjectNode root = toml.load(installer.configFile(ctx));
    assertThat(root.path("mcp_servers").path("pieria").path("command").asString())
      .isEqualTo("/opt/pieria/bin/pieria-gateway");
    assertThat(root.path("mcp_servers").path("pieria").path("env").path("PIERIA_DAEMON_URL").asString())
      .isEqualTo("http://127.0.0.1:8077");

    ArrayNode hooks = (ArrayNode) root.path("hooks");
    assertThat(hooks.size()).isEqualTo(2);
    assertThat(hooks.get(0).path("command").asString()).contains("codex").contains("stop.sh");
    assertThat(Files.exists(ctx.harnessDir().resolve("codex").resolve("stop.sh"))).isTrue();
  }

  @Test
  void installIsIdempotent(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "p");
    installer.install(ctx);
    installer.install(ctx);
    ArrayNode hooks = (ArrayNode) toml.load(installer.configFile(ctx)).path("hooks");
    assertThat(hooks.size()).isEqualTo(2);
  }

  @Test
  void uninstallRemovesOnlyPieria(@TempDir Path tmp) throws IOException {
    WiringContext ctx = ctx(tmp, "p");
    Path config = installer.configFile(ctx);
    Files.createDirectories(config.getParent());
    Files.writeString(config, "model = \"gpt-5\"\nhooks = [{event = 'Stop', command = 'echo other'}]\n");

    installer.install(ctx);
    assertThat(installer.isInstalled(ctx)).isTrue();
    installer.uninstall(ctx);
    assertThat(installer.isInstalled(ctx)).isFalse();

    ObjectNode root = toml.load(config);
    assertThat(root.path("model").asString()).isEqualTo("gpt-5");
    ArrayNode hooks = (ArrayNode) root.path("hooks");
    assertThat(hooks.size()).isEqualTo(1);
    assertThat(hooks.get(0).path("command").asString()).isEqualTo("echo other");
  }
}
