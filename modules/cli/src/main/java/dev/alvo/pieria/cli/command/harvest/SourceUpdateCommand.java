package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.api.request.UpdateSourceRequest;
import dev.alvo.pieria.api.response.HarvesterResponse;
import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

@Command(name = "update", description = "Change a source's settings, keeping its progress.", mixinStandardHelpOptions = true)
public final class SourceUpdateCommand extends AbstractSourceCommand {

  @Parameters(index = "0", paramLabel = "<id>", description = "Source id.")
  String id;

  @Option(names = "--set", paramLabel = "key=value", description = "A setting. Repeatable.")
  List<String> settings = new ArrayList<>();

  @Option(names = "--interval", description = "How often to run, such as 30m, 6h or 1d.")
  String interval;

  @Option(names = "--mode", description = "full or raw.")
  String mode;

  @Option(names = "--max-items", description = "Most items one run may process.")
  Integer maxItems;

  @Option(names = "--max-chars", description = "Most characters one run may process.")
  Long maxChars;

  @Option(names = "--reset-cursor", description = "Start again from the beginning on the next run.")
  boolean resetCursor;

  @Override
  protected int run(HarvestClient client) {
    String profile = profile();
    ObjectNode config = null;
    if (!settings.isEmpty()) {
      String harvesterId = client.get(profile, id).harvester();
      HarvesterResponse harvester = client.harvesters().harvesters().stream()
        .filter(candidate -> candidate.id().equals(harvesterId)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("harvester " + harvesterId + " is not installed"));
      config = SourceOptions.parseConfig(settings, harvester);
    }
    client.update(profile, id, new UpdateSourceRequest(config, mode, interval == null ? null : SourceOptions.parseInterval(interval),
      maxItems, maxChars, resetCursor ? Boolean.TRUE : null));
    log.info("Updated source {}.", id);
    return 0;
  }
}
