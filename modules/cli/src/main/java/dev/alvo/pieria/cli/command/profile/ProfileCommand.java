package dev.alvo.pieria.cli.command.profile;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code pieria profile} — work with memory profiles and their contents in the local daemon:
 * list profiles, inspect statistics, and list/recall/store/forget/export memories. {@code resolve}
 * preserves the original behavior of printing the profile slug for a directory.
 */
@Command(
  name = "profile",
  description = "Inspect and manage memory profiles and their memories.",
  mixinStandardHelpOptions = true,
  subcommands = {
    ProfileListCommand.class,
    ProfileStatsCommand.class,
    ProfileMemoriesCommand.class,
    ProfileRecallCommand.class,
    ProfileRememberCommand.class,
    ProfileForgetCommand.class,
    ProfileExportCommand.class,
    ProfileResolveCommand.class
  }
)
public final class ProfileCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
