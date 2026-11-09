package dev.alvo.pieria.cli.command.harvest;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** {@code pieria harvester}: install and manage the plugins that bring in outside knowledge. */
@Command(
  name = "harvester",
  description = "Install and manage knowledge harvesters.",
  mixinStandardHelpOptions = true,
  subcommands = {
    HarvesterListCommand.class,
    HarvesterInstallCommand.class,
    HarvesterRemoveCommand.class,
    HarvesterDoctorCommand.class
  }
)
public final class HarvesterCommand implements Runnable {

  @Spec
  CommandSpec spec;

  @Override
  public void run() {
    throw new CommandLine.ParameterException(spec.commandLine(), "Specify a subcommand.");
  }
}
