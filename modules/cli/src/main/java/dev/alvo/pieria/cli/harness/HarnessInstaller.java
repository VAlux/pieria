package dev.alvo.pieria.cli.harness;

import java.io.IOException;
import java.util.List;

/**
 * Wires one AI-agent harness into Pieria: registers the MCP gateway and installs the lifecycle
 * hooks. Implementations are idempotent — installing twice yields one set of entries, and
 * uninstall removes only Pieria's entries, leaving unrelated config untouched.
 */
public interface HarnessInstaller {

  /** Stable identifier used on the command line (e.g. {@code "claude-code"}). */
  String id();

  /**
   * Embedded hook-script resources this harness needs, as classpath paths
   * (e.g. {@code "harness/claude-code/stop.sh"}). The shared scripts
   * {@code harness/profile-name.sh} and {@code harness/ingest.sh} are always included.
   */
  List<String> requiredScriptResources();

  /** Register the MCP server and install hooks for the given context. */
  void install(WiringContext ctx) throws IOException;

  /** Remove only Pieria's MCP server and hook entries. */
  void uninstall(WiringContext ctx) throws IOException;

  /** True if Pieria's MCP server is currently registered for this harness/scope. */
  boolean isInstalled(WiringContext ctx) throws IOException;
}
