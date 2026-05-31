package dev.alvo.pieria.cli.modules.daemon;

/**
 * Builds the human-readable "Pieria is ready" banner shown in the user's terminal after the daemon
 * comes up: the local daemon URL, how profiles are resolved, the {@code pieria harness install}
 * hint, and an equivalent manual MCP config snippet referencing the gateway.
 *
 * <p>This lives in the CLI (not the daemon) because a CLI-spawned daemon redirects its stdout to a
 * log file — the banner only reaches a human when printed from the foreground CLI process. Kept free
 * of secrets and provider URLs.
 */
public final class StartupSummary {

  private StartupSummary() {
  }

  /**
   * @param daemonUrl      the daemon base URL (e.g. {@code http://127.0.0.1:8077})
   * @param gatewayCommand absolute path (or bare name) of the {@code pieria-gateway} executable
   */
  public static String render(String daemonUrl, String gatewayCommand) {
    String snippet = """
      {
        "mcpServers": {
          "pieria": {
            "command": "%s",
            "env": {
              "PIERIA_DAEMON_URL": "%s"
            }
          }
        }
      }""".formatted(gatewayCommand, daemonUrl);

    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder();
    sb.append(nl);
    sb.append("=== Pieria is ready ===").append(nl);
    sb.append("Daemon URL: ").append(daemonUrl).append(nl);
    sb.append("Profiles: each working directory maps to one memory profile, resolved from "
        + "$PIERIA_PROFILE, else the git remote repo name, else the directory basename "
        + "(normalized to a lowercase slug; empty -> \"default\").")
      .append(nl);
    sb.append("Harness setup: run 'pieria harness install claude-code' (or 'codex') from your "
        + "project to register the MCP gateway and lifecycle hooks automatically.")
      .append(nl);
    sb.append("Manual MCP config (equivalent), if you prefer to wire it by hand:").append(nl);
    sb.append(snippet).append(nl);
    sb.append("=======================").append(nl);
    return sb.toString();
  }
}
