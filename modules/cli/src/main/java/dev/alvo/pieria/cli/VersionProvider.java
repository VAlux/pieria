package dev.alvo.pieria.cli;

import dev.alvo.pieria.cli.modules.update.BuildInfo;
import picocli.CommandLine.IVersionProvider;

/**
 * Supplies {@code pieria --version} from the embedded build stamp, so the reported version tracks
 * the actual binary rather than a hard-coded string.
 */
public final class VersionProvider implements IVersionProvider {

  @Override
  public String[] getVersion() {
    return new String[] {"pieria " + BuildInfo.current()};
  }
}
