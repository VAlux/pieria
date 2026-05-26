package dev.alvo.pieria.cli.modules.harness;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Builds a {@link WiringContext} from command-line options, filling defaults from the environment.
 */
public final class WiringContextFactory {

  private static final String DEFAULT_DAEMON_URL = "http://127.0.0.1:8077";

  private WiringContextFactory() {
  }

  public static WiringContext from(Scope scope, Path projectDir, String profile, String daemonUrl,
                                   boolean dryRun, PrintStream out) {
    PathResolver paths = PathResolver.create();
    String resolvedDaemon = (daemonUrl != null && !daemonUrl.isBlank())
      ? daemonUrl
      : System.getenv().getOrDefault("PIERIA_DAEMON_URL", DEFAULT_DAEMON_URL);
    return new WiringContext(
      scope,
      projectDir.toAbsolutePath().normalize(),
      paths.userHome(),
      paths.gatewayCommand(),
      paths.harnessDir(),
      profile,
      resolvedDaemon,
      dryRun,
      out
    );
  }
}
