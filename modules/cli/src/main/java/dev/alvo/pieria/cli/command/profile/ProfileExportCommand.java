package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.cli.modules.daemon.ProfileApiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code pieria profile export <name> [--out file]} — export all of a profile's memories as NDJSON,
 * to a file when {@code --out} is given, otherwise to stdout.
 */
@Command(
  name = "export",
  description = "Export all memories for a profile as NDJSON.",
  mixinStandardHelpOptions = true
)
public final class ProfileExportCommand extends AbstractProfileCommand {

  @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
  String name;

  @Option(names = "--out", description = "Write NDJSON to this file instead of stdout.")
  Path out;

  @Override
  protected int run(ProfileApiClient client) throws Exception {
    String ndjson = client.export(name);

    if (out == null) {
      log.print(ndjson);
      return 0;
    }

    Files.writeString(out, ndjson, StandardCharsets.UTF_8);
    log.error("Wrote export to {}", out.toAbsolutePath());
    return 0;
  }
}
