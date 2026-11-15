package dev.alvo.pieria.cli.command.harvest;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** {@code pieria source}: configure and run what the installed harvesters collect. */
@Command(
  name = "source",
  description = "Configure and run harvest sources.",
  mixinStandardHelpOptions = true,
  subcommands = {
    SourceAddCommand.class,
    SourceListCommand.class,
    SourceShowCommand.class,
    SourceUpdateCommand.class,
    SourceCheckCommand.class,
    SourceAuthorizeCommand.class,
    SourceRunCommand.class,
    SourceRunsCommand.class,
    SourcePauseCommand.class,
    SourceResumeCommand.class,
    SourceAcceptSweepCommand.class,
    SourceSecretCommand.class,
    SourceRemoveCommand.class
  }
)
public final class SourceCommand implements Runnable {

  @Spec
  CommandSpec spec;

  @Override
  public void run() {
    throw new CommandLine.ParameterException(spec.commandLine(), "Specify a subcommand.");
  }
}
