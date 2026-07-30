package dev.alvo.pieria.cli.modules.harness;

import java.io.IOException;

/**
 * Wires one AI-agent harness into Pieria: registers the MCP gateway and installs the lifecycle
 * hooks. Implementations are idempotent — installing twice yields one set of entries, and
 * uninstall removes only Pieria's entries, leaving unrelated config untouched.
 */
public interface HarnessInstaller {

  /**
   * Stable identifier used on the command line (e.g. {@code "claude-code"}).
   */
  String id();

  /**
   * Register the MCP server and install hooks for the given context.
   */
  void install(WiringContext ctx) throws IOException;

  /**
   * Remove only Pieria's MCP server and hook entries.
   */
  void uninstall(WiringContext ctx) throws IOException;

  /**
   * True if Pieria's MCP server is currently registered for this harness/scope.
   */
  boolean isInstalled(WiringContext ctx) throws IOException;
}
