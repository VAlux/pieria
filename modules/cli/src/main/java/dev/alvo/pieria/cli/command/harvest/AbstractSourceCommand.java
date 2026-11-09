package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.mapping.ProfileResolver;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/** A command that works on the sources of one profile. */
abstract class AbstractSourceCommand extends AbstractHarvestCommand {

  @Option(names = "--profile", description = "Profile name (default: resolved from the current directory).")
  String profile;

  protected final String profile() {
    if (profile != null && !profile.isBlank()) {
      return ProfileResolver.normalize(profile);
    }
    return ProfileResolver.create(Path.of("").toAbsolutePath()).resolve();
  }
}
