package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.api.request.CreateSourceRequest;
import dev.alvo.pieria.api.response.HarvesterResponse;
import dev.alvo.pieria.api.response.SourceCheckResponse;
import dev.alvo.pieria.api.response.SourceResponse;
import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.node.ObjectNode;

import java.io.Console;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates a source. Secrets come from the environment or a hidden prompt and are never accepted as
 * option values, where they would end up in shell history.
 */
@Command(name = "add", description = "Create a source that uses an installed harvester.", mixinStandardHelpOptions = true)
public final class SourceAddCommand extends AbstractSourceCommand {

  @Parameters(index = "0", paramLabel = "<harvester>", description = "Harvester id.")
  String harvesterId;

  @Option(names = "--id", required = true, description = "Id for the new source.")
  String id;

  @Option(names = "--set", paramLabel = "key=value", description = "A setting. Repeatable.")
  List<String> settings = new ArrayList<>();

  @Option(names = "--secret-env", paramLabel = "name=ENV_VAR", description = "Read a secret from an environment variable. Repeatable.")
  List<String> secretEnv = new ArrayList<>();

  @Option(names = "--interval", description = "How often to run, such as 30m, 6h or 1d (default: 6h).")
  String interval;

  @Option(names = "--mode", description = "full extracts memories; raw only makes the text searchable (default: full).")
  String mode;

  @Option(names = "--max-items", description = "Most items one run may process.")
  Integer maxItems;

  @Option(names = "--max-chars", description = "Most characters one run may process.")
  Long maxChars;

  @Option(names = "--start", description = "Start running on schedule now, instead of waiting for 'source resume'.")
  boolean start;

  @Override
  protected int run(HarvestClient client) {
    HarvesterResponse harvester = client.harvesters().harvesters().stream()
      .filter(candidate -> candidate.id().equals(harvesterId)).findFirst().orElse(null);
    if (harvester == null) {
      log.error("Harvester {} is not installed. See 'pieria harvester list'.", harvesterId);
      return 4;
    }
    ObjectNode config = SourceOptions.parseConfig(settings, harvester);
    Map<String, String> secrets = new LinkedHashMap<>(SourceOptions.secretsFromEnvironment(secretEnv));
    fillRequired(harvester, config, secrets);

    String profile = profile();
    SourceResponse created = client.create(profile, new CreateSourceRequest(id, harvesterId, config, secrets, mode,
      interval == null ? null : SourceOptions.parseInterval(interval), maxItems, maxChars, start ? Boolean.TRUE : null));
    log.info("Created source {} in profile {}.", created.id(), profile);
    created.warnings().forEach(warning -> log.info("Warning: {}", warning));

    // A sign-in harvester saves its own credential on `authorize`; until every secret it declares is
    // set, its check can only fail.
    boolean everySecretGiven = harvester.fields().stream().filter(field -> field.kind().equals("secret"))
      .allMatch(field -> secrets.containsKey(field.key()));
    if (harvester.capabilities().contains("oauth-device-code") && !everySecretGiven) {
      log.info("Next: sign in with 'pieria source authorize {}'.", id);
      return 0;
    }
    SourceCheckResponse check = client.check(profile, id);
    if (!check.ok()) {
      log.error("The source's check failed: {}", check.message());
      log.error("Fix its settings with 'pieria source update {}' or its credentials with 'pieria source secret {}'.", id, id);
      return 1;
    }
    log.info("The check passed.");
    if (start) {
      log.info("It will run on schedule.");
    } else {
      log.info("Next: preview it with 'pieria source run {} --dry-run', then start it with 'pieria source resume {}'.", id, id);
    }
    return 0;
  }

  private void fillRequired(HarvesterResponse harvester, ObjectNode config, Map<String, String> secrets) {
    Console console = interactiveConsole();
    for (HarvesterResponse.Field field : harvester.fields()) {
      if (!field.required()) {
        continue;
      }
      if (field.kind().equals("secret")) {
        if (!secrets.containsKey(field.key())) {
          if (console == null) {
            throw new IllegalArgumentException("missing required secret " + field.key() + ": use --secret-env " + field.key() + "=ENV_VAR");
          }
          secrets.put(field.key(), new String(console.readPassword("%s: ", field.label())));
        }
      } else if (!config.has(field.key())) {
        if (field.defaultValue() != null && !field.defaultValue().isNull()) {
          config.set(field.key(), field.defaultValue());
        } else if (console == null) {
          throw new IllegalArgumentException("missing required setting " + field.key() + ": use --set " + field.key() + "=VALUE");
        } else {
          config.set(field.key(), SourceOptions.value(field, console.readLine("%s: ", field.label())));
        }
      }
    }
  }
}
