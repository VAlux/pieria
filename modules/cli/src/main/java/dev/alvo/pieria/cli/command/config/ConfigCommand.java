package dev.alvo.pieria.cli.command.config;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code pieria config} — work with the layered Pieria configuration: the global
 * {@code config.toml} (OS config dir) overridden by a project's {@code .pieria/config.toml}.
 * {@code sync} pushes the merged daemon-overridable subset to the project's profile;
 * {@code show} prints the profile's effective config as the daemon resolves it.
 */
@Command(
  name = "config",
  description = "Inspect and sync the layered (global + project .pieria) configuration.",
  mixinStandardHelpOptions = true,
  subcommands = {
    ConfigSyncCommand.class,
    ConfigShowCommand.class
  }
)
public final class ConfigCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
