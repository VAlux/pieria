package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.api.response.SourceCheckResponse;
import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "check", description = "Check a source's settings and credentials.", mixinStandardHelpOptions = true)
public final class SourceCheckCommand extends AbstractSourceCommand {

  @Parameters(index = "0", paramLabel = "<id>", description = "Source id.")
  String id;

  @Override
  protected int run(HarvestClient client) {
    SourceCheckResponse check = client.check(profile(), id);
    if (!check.ok()) {
      log.error("The check failed ({}): {}", check.errorClass(), check.message());
      return 1;
    }
    log.info("The check passed.");
    return 0;
  }
}
