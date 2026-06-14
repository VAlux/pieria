package dev.alvo.pieria.cli.modules.harness;

import dev.alvo.pieria.cli.log.Logger;
import java.nio.file.Path;

/**
 * Everything a {@link HarnessInstaller} needs to wire (or unwire) one harness.
 *
 * @param scope          project- vs user-level config target
 * @param projectDir     the project/repo directory (used for project scope)
 * @param userHome       the user home directory (used for user scope)
 * @param gatewayCommand absolute path to the {@code pieria-gateway} executable for the MCP command
 * @param harnessDir     directory the hook scripts are extracted to ({@code PIERIA_HOME/harness})
 * @param profile        explicit profile slug, or null/blank to leave empty for auto-derivation
 * @param daemonUrl      daemon base URL injected into the MCP server env
 * @param dryRun         when true, print intended changes without writing
 * @param log            logger for human-readable output
 */
public record WiringContext(
  Scope scope,
  Path projectDir,
  Path userHome,
  String gatewayCommand,
  Path harnessDir,
  String profile,
  String daemonUrl,
  boolean dryRun,
  Logger log
) {

  /**
   * Base directory for scope-relative config files.
   */
  public Path baseDir() {
    return scope == Scope.USER ? userHome : projectDir;
  }

  /**
   * True when an explicit, non-blank profile was provided.
   */
  public boolean hasProfile() {
    return profile != null && !profile.isBlank();
  }
}
