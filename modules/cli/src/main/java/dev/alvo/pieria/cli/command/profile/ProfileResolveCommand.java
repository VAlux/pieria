package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.mapping.ProfileResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code pieria profile resolve} — print the Pieria profile slug for a directory, using the same
 * resolution as the gateway and hooks ({@link ProfileResolver}: {@code $PIERIA_PROFILE} → git remote
 * → dir name). No daemon contact; purely local.
 */
@Command(
  name = "resolve",
  description = "Print the resolved Pieria profile slug for the current (or given) directory.",
  mixinStandardHelpOptions = true
)
public final class ProfileResolveCommand implements Callable<Integer> {

  @Option(names = "--project-dir", description = "Directory to resolve the profile for (default: current directory).")
  Path projectDir = Path.of("");

  private final Logger log = new Logger();

  @Override
  public Integer call() {
    Path dir = projectDir.toAbsolutePath().normalize();
    log.info(ProfileResolver.create(dir).resolve());
    return 0;
  }
}
